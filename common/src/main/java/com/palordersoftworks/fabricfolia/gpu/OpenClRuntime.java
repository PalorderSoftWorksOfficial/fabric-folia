/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hand-rolled OpenCL 1.2 binding over JDK 25 FFM (~20 entry points) plus the
 * AABB-overlap kernel backend. Why hand-rolled: OpenCL's ABI is stable C, the
 * surface we need is tiny, and a dependency-free binding keeps the mod's jar
 * self-contained on all three platforms (the ICD loader — OpenCL.dll /
 * libOpenCL.so.1 / OpenCL.framework — ships with GPU drivers).
 *
 * <p>Why-comment on threading: OpenCL command queues allow concurrent enqueues
 * from multiple threads, but kernel args are per-kernel state, so each
 * overlapMask call is serialized on the instance. That matches the subsystem
 * contract (queries are short; batching amortizes the queue).</p>
 */
final class OpenClRuntime implements GpuBackend {

	private static final int CL_SUCCESS = 0;
	private static final int CL_DEVICE_NOT_FOUND = -1;

	private static final long CL_DEVICE_TYPE_CPU = 1L << 1;
	private static final long CL_DEVICE_TYPE_GPU = 1L << 2;
	private static final long CL_DEVICE_TYPE_ALL = 0xFFFFFFFFL;

	private static final int CL_PLATFORM_NAME = 0x0902;
	private static final int CL_PLATFORM_VENDOR = 0x0903;

	private static final int CL_DEVICE_TYPE = 0x1000;
	private static final int CL_DEVICE_MAX_COMPUTE_UNITS = 0x1002;
	private static final int CL_DEVICE_MAX_CLOCK_FREQUENCY = 0x100C;
	private static final int CL_DEVICE_GLOBAL_MEM_SIZE = 0x101F;
	private static final int CL_DEVICE_NAME = 0x102B;
	private static final int CL_DEVICE_VENDOR = 0x102C;
	private static final int CL_DEVICE_VERSION = 0x102F;
	private static final int CL_DEVICE_DOUBLE_FP_CONFIG = 0x1032;

	private static final int CL_PROGRAM_BUILD_LOG = 0x1183;

	private static final long CL_MEM_READ_WRITE = 1L;
	private static final long CL_MEM_COPY_HOST_PTR = 1L << 5;

	private static final int CL_TRUE = 1;

	/**
	 * Kernel mirrors vanilla AABB#intersects bit-exactly: strict inequalities,
	 * and NO arithmetic on the doubles — pure IEEE-754 comparisons produce the
	 * same results as the CPU reference on any conformant device. The odd
	 * indices stay grouped so one work-item handles one box.
	 */
	private static final String KERNEL_SOURCE = """
			#pragma OPENCL EXTENSION cl_khr_fp64 : enable

			__kernel void aabb_overlap_mask(__global const double* boxes,
			                                const double q0, const double q1, const double q2,
			                                const double q3, const double q4, const double q5,
			                                __global int* mask, const int n) {
			    int i = (int) get_global_id(0);
			    if (i >= n) {
			        return;
			    }
			    int b = i * 6;
			    mask[i] = (boxes[b]     < q3 && boxes[b + 3] > q0 &&
			               boxes[b + 1] < q4 && boxes[b + 4] > q1 &&
			               boxes[b + 2] < q5 && boxes[b + 5] > q2) ? 1 : 0;
			}
			""";

	private static final Linker LINKER = Linker.nativeLinker();
	private static volatile Api api;
	private static volatile String apiFailure;

	record DeviceHandle(MemorySegment handle, GpuDevice info) {
	}

	record Discovery(List<DeviceHandle> devices, String failure) {
	}

	private final Api cl;
	private final MemorySegment context;
	private final MemorySegment queue;
	private final MemorySegment program;
	private final MemorySegment kernel;
	private final GpuDevice device;
	private final java.util.concurrent.atomic.AtomicBoolean closed =
			new java.util.concurrent.atomic.AtomicBoolean();

	private OpenClRuntime(Api cl, MemorySegment context, MemorySegment queue,
			MemorySegment program, MemorySegment kernel, GpuDevice device) {
		this.cl = cl;
		this.context = context;
		this.queue = queue;
		this.program = program;
		this.kernel = kernel;
		this.device = device;
	}

	// ------------------------------------------------------------------
	// Runtime loading
	// ------------------------------------------------------------------

	/** @return the process-wide OpenCL API table, loading the ICD loader once. */
	static Api api() {
		Api a = api;
		if (a != null) {
			return a;
		}
		synchronized (OpenClRuntime.class) {
			if (api == null) {
				try {
					api = new Api(loadLibrary());
				} catch (Throwable t) {
					apiFailure = t.getMessage();
					throw new GpuException("OpenCL runtime unavailable: " + t.getMessage(), t);
				}
			}
			return api;
		}
	}

	/** @return the load-failure reason recorded by the last api() attempt. */
	static String apiFailureReason() {
		return apiFailure == null ? "" : apiFailure;
	}

	private static SymbolLookup loadLibrary() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String[] candidates = os.contains("win")
				? new String[] {"OpenCL"}
				: (os.contains("mac") || os.contains("darwin"))
						? new String[] {"OpenCL", "/System/Library/Frameworks/OpenCL.framework/OpenCL"}
						: new String[] {"OpenCL", "libOpenCL.so.1", "libOpenCL.so"};
		RuntimeException last = null;
		for (String name : candidates) {
			try {
				return SymbolLookup.libraryLookup(name, Arena.global());
			} catch (RuntimeException e) {
				last = e;
			}
		}
		throw new GpuException("no OpenCL ICD loader found (tried "
				+ String.join(", ", candidates) + "): " + (last == null ? "unknown" : last.getMessage()), last);
	}

	// ------------------------------------------------------------------
	// Discovery
	// ------------------------------------------------------------------

	/**
	 * Enumerates every platform and device. Never throws for "no devices";
	 * only a missing ICD loader surfaces as failure text inside the result.
	 * Device handles are allocated in the global arena on purpose: they are
	 * used later from arbitrary worker threads after this call returns.
	 */
	static Discovery discover() {
		List<DeviceHandle> found = new ArrayList<>();
		Api a;
		try {
			a = api();
		} catch (GpuException e) {
			return new Discovery(List.of(), e.getMessage());
		}
		try {
			MemorySegment platformCount = Arena.global().allocate(ValueLayout.JAVA_INT);
			check((int) a.getPlatformIDs.invoke(0, MemorySegment.NULL, platformCount), "clGetPlatformIDs");
			int platforms = platformCount.get(ValueLayout.JAVA_INT, 0);
			if (platforms == 0) {
				return new Discovery(List.of(), "no OpenCL platforms registered (ICD list empty)");
			}
			MemorySegment platformIds = Arena.global().allocate((long) platforms * ValueLayout.ADDRESS.byteSize());
			check((int) a.getPlatformIDs.invoke(platforms, platformIds, MemorySegment.NULL), "clGetPlatformIDs");
			for (int p = 0; p < platforms; p++) {
				MemorySegment platform = platformIds.get(ValueLayout.ADDRESS, (long) p * ValueLayout.ADDRESS.byteSize());
				String platformName = infoString(a, platform, CL_PLATFORM_NAME, true);
				String platformVendor = infoString(a, platform, CL_PLATFORM_VENDOR, true);
				MemorySegment deviceCount = Arena.global().allocate(ValueLayout.JAVA_INT);
				int rc = (int) a.getDeviceIDs.invoke(platform, CL_DEVICE_TYPE_ALL, 0,
						MemorySegment.NULL, deviceCount);
				if (rc == CL_DEVICE_NOT_FOUND) {
					continue;
				}
				check(rc, "clGetDeviceIDs(count)");
				int devices = deviceCount.get(ValueLayout.JAVA_INT, 0);
				if (devices == 0) {
					continue;
				}
				MemorySegment deviceIds = Arena.global().allocate((long) devices * ValueLayout.ADDRESS.byteSize());
				check((int) a.getDeviceIDs.invoke(platform, CL_DEVICE_TYPE_ALL, devices,
						deviceIds, MemorySegment.NULL), "clGetDeviceIDs");
				for (int d = 0; d < devices; d++) {
					MemorySegment dev = deviceIds.get(ValueLayout.ADDRESS, (long) d * ValueLayout.ADDRESS.byteSize());
					long type = infoLong(a, dev, CL_DEVICE_TYPE, ValueLayout.JAVA_LONG, 0);
					GpuDevice info = new GpuDevice(platformName,
							infoString(a, dev, CL_DEVICE_NAME, false),
							platformVendor + " / " + infoString(a, dev, CL_DEVICE_VENDOR, false),
							(type & CL_DEVICE_TYPE_GPU) != 0,
							(int) infoLong(a, dev, CL_DEVICE_MAX_COMPUTE_UNITS, ValueLayout.JAVA_INT, 0),
							infoLong(a, dev, CL_DEVICE_GLOBAL_MEM_SIZE, ValueLayout.JAVA_LONG, 0),
							(int) infoLong(a, dev, CL_DEVICE_MAX_CLOCK_FREQUENCY, ValueLayout.JAVA_INT, 0),
							infoLong(a, dev, CL_DEVICE_DOUBLE_FP_CONFIG, ValueLayout.JAVA_LONG, -1) != 0);
					found.add(new DeviceHandle(dev, info));
				}
			}
			return new Discovery(List.copyOf(found), found.isEmpty() ? "no OpenCL devices on any platform" : "");
		} catch (GpuException e) {
			return new Discovery(List.of(), e.getMessage());
		} catch (Throwable t) {
			return new Discovery(List.of(), "OpenCL discovery failed: " + t);
		}
	}

	/**
	 * Picks the device to use. {@code pref} blank = prefer GPU; "gpu"/"cpu" =
	 * type; anything else = case-insensitive substring on name/vendor/platform.
	 *
	 * @throws GpuException when nothing matches (message lists what exists)
	 */
	static DeviceHandle select(List<DeviceHandle> devices, String pref) {
		if (devices.isEmpty()) {
			throw new GpuException("no OpenCL devices available");
		}
		String p = pref == null ? "" : pref.trim().toLowerCase(Locale.ROOT);
		if (p.isEmpty() || p.equals("gpu")) {
			for (DeviceHandle d : devices) {
				if (d.info().gpu()) {
					return d;
				}
			}
			if (p.equals("gpu")) {
				throw new GpuException("gpu.device=gpu but no GPU device found; available: " + describeAll(devices));
			}
			return devices.get(0);
		}
		if (p.equals("cpu")) {
			for (DeviceHandle d : devices) {
				if (!d.info().gpu()) {
					return d;
				}
			}
			throw new GpuException("gpu.device=cpu but no CPU device found; available: " + describeAll(devices));
		}
		DeviceHandle nonGpuMatch = null;
		for (DeviceHandle d : devices) {
			GpuDevice i = d.info();
			boolean matches = i.name().toLowerCase(Locale.ROOT).contains(p)
					|| i.vendor().toLowerCase(Locale.ROOT).contains(p)
					|| i.platform().toLowerCase(Locale.ROOT).contains(p);
			if (matches) {
				if (i.gpu()) {
					return d;
				}
				if (nonGpuMatch == null) {
					nonGpuMatch = d;
				}
			}
		}
		if (nonGpuMatch != null) {
			return nonGpuMatch;
		}
		throw new GpuException("gpu.device '" + pref + "' matched no OpenCL device; available: " + describeAll(devices));
	}

	private static String describeAll(List<DeviceHandle> devices) {
		StringBuilder sb = new StringBuilder();
		for (DeviceHandle d : devices) {
			if (sb.length() > 0) {
				sb.append("; ");
			}
			sb.append(d.info().platform()).append(" / ").append(d.info().name());
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// Context / program / kernel lifecycle
	// ------------------------------------------------------------------

	/**
	 * Builds context, queue, program, and kernel for the device. Releases any
	 * partially created handles before rethrowing (no leaks on build failure).
	 */
	static OpenClRuntime open(DeviceHandle device) {
		Api a = api();
		if (!device.info().fp64()) {
			// The kernel needs exact double semantics; a doubles-incapable
			// device can never be parity-exact, so it is unusable by design.
			throw new GpuException("device lacks fp64 support: " + device.info().name());
		}
		MemorySegment context = MemorySegment.NULL;
		MemorySegment queue = MemorySegment.NULL;
		MemorySegment program = MemorySegment.NULL;
		MemorySegment kernel = MemorySegment.NULL;
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment err = ar.allocate(ValueLayout.JAVA_INT);
			// devices is a pointer to an ARRAY of handles, not a handle: passing
			// the raw handle makes the ICD dereference driver memory as a device
			// pointer (found the hard way: EXCEPTION_ACCESS_VIOLATION in
			// nvopencl64 when props is NULL and the platform is derived).
			MemorySegment deviceArray = ar.allocate(ValueLayout.ADDRESS);
			deviceArray.set(ValueLayout.ADDRESS, 0, device.handle());
			context = (MemorySegment) a.createContext.invoke(MemorySegment.NULL, 1,
					deviceArray, MemorySegment.NULL, MemorySegment.NULL, err);
			check(err, "clCreateContext");
			queue = (MemorySegment) a.createCommandQueue.invoke(context, device.handle(), 0L, err);
			check(err, "clCreateCommandQueue");
			MemorySegment source = ar.allocateFrom(KERNEL_SOURCE);
			MemorySegment sourcePtr = ar.allocate(ValueLayout.ADDRESS);
			sourcePtr.set(ValueLayout.ADDRESS, 0, source);
			program = (MemorySegment) a.createProgramWithSource.invoke(context, 1,
					sourcePtr, MemorySegment.NULL, err);
			check(err, "clCreateProgramWithSource");
			// num_devices=0 + NULL list builds for every device in the context;
			// avoids repeating the pointer-to-array contract of device_list.
			int rc = (int) a.buildProgram.invoke(program, 0, MemorySegment.NULL,
					MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
			if (rc != CL_SUCCESS) {
				throw new GpuException("clBuildProgram failed (" + errorName(rc) + "): "
						+ buildLog(a, program, device.handle()));
			}
			kernel = (MemorySegment) a.createKernel.invoke(program,
					ar.allocateFrom("aabb_overlap_mask"), err);
			check(err, "clCreateKernel");
			return new OpenClRuntime(a, context, queue, program, kernel, device.info());
		} catch (Throwable t) {
			release(a, kernel, a.releaseKernel);
			release(a, program, a.releaseProgram);
			release(a, queue, a.releaseCommandQueue);
			release(a, context, a.releaseContext);
			if (t instanceof GpuException ge) {
				throw ge;
			}
			throw new GpuException("OpenCL setup failed for " + device.info().name() + ": " + t, t);
		}
	}

	// ------------------------------------------------------------------
	// Kernel dispatch
	// ------------------------------------------------------------------

	@Override
	public synchronized int[] overlapMask(double[] boxes, int count, double[] query) {
		if (count == 0) {
			return new int[0];
		}
		if (closed.get()) {
			throw new GpuException("OpenCL backend already closed");
		}
		MemorySegment boxBuf = MemorySegment.NULL;
		MemorySegment maskBuf = MemorySegment.NULL;
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment boxData = ar.allocate((long) count * 6 * ValueLayout.JAVA_DOUBLE.byteSize());
			boxData.copyFrom(MemorySegment.ofArray(boxes)
					.asSlice(0, (long) count * 6 * ValueLayout.JAVA_DOUBLE.byteSize()));
			MemorySegment maskData = ar.allocate((long) count * Integer.BYTES);
			MemorySegment err = ar.allocate(ValueLayout.JAVA_INT);

			boxBuf = (MemorySegment) invoke(cl.createBuffer, context,
					CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, boxData.byteSize(), boxData, err);
			check(err, "clCreateBuffer(boxes)");
			maskBuf = (MemorySegment) invoke(cl.createBuffer, context,
					CL_MEM_READ_WRITE, maskData.byteSize(), MemorySegment.NULL, err);
			check(err, "clCreateBuffer(mask)");

			setArg(0, ar, ValueLayout.ADDRESS, boxBuf);
			setArg(1, ar, ValueLayout.JAVA_DOUBLE, query[0]);
			setArg(2, ar, ValueLayout.JAVA_DOUBLE, query[1]);
			setArg(3, ar, ValueLayout.JAVA_DOUBLE, query[2]);
			setArg(4, ar, ValueLayout.JAVA_DOUBLE, query[3]);
			setArg(5, ar, ValueLayout.JAVA_DOUBLE, query[4]);
			setArg(6, ar, ValueLayout.JAVA_DOUBLE, query[5]);
			setArg(7, ar, ValueLayout.ADDRESS, maskBuf);
			setArg(8, ar, ValueLayout.JAVA_INT, count);

			MemorySegment globalSize = ar.allocate(ValueLayout.JAVA_LONG);
			globalSize.set(ValueLayout.JAVA_LONG, 0, (long) count);
			check((int) invoke(cl.enqueueNDRangeKernel, queue, kernel, 1, MemorySegment.NULL,
					globalSize, MemorySegment.NULL, 0, MemorySegment.NULL, MemorySegment.NULL),
					"clEnqueueNDRangeKernel");
			check((int) invoke(cl.finish, queue), "clFinish");
			check((int) invoke(cl.enqueueReadBuffer, queue, maskBuf, CL_TRUE, 0L,
					maskData.byteSize(), maskData, 0, MemorySegment.NULL, MemorySegment.NULL),
					"clEnqueueReadBuffer");

			int[] out = new int[count];
			MemorySegment.ofArray(out).copyFrom(maskData);
			return out;
		} finally {
			release(cl, boxBuf, cl.releaseMem);
			release(cl, maskBuf, cl.releaseMem);
		}
	}

	private void setArg(int index, Arena ar, ValueLayout layout, Object value) {
		MemorySegment slot = ar.allocate(layout);
		if (layout == ValueLayout.ADDRESS) {
			slot.set(ValueLayout.ADDRESS, 0, (MemorySegment) value);
		} else if (layout == ValueLayout.JAVA_DOUBLE) {
			slot.set(ValueLayout.JAVA_DOUBLE, 0, (Double) value);
		} else {
			slot.set(ValueLayout.JAVA_INT, 0, (Integer) value);
		}
		check((int) invoke(cl.setKernelArg, kernel, index, layout.byteSize(), slot), "clSetKernelArg(" + index + ")");
	}

	@Override
	public boolean isGpu() {
		return true;
	}

	@Override
	public String describe() {
		return device.platform() + " / " + device.summary();
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			release(cl, kernel, cl.releaseKernel);
			release(cl, program, cl.releaseProgram);
			release(cl, queue, cl.releaseCommandQueue);
			release(cl, context, cl.releaseContext);
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private static Object invoke(MethodHandle mh, Object... args) {
		try {
			return mh.invokeWithArguments(args);
		} catch (Throwable t) {
			if (t instanceof RuntimeException re) {
				throw re;
			}
			if (t instanceof Error e) {
				throw e;
			}
			throw new GpuException("OpenCL call failed: " + t, t);
		}
	}

	private static void release(Api a, MemorySegment handle, MethodHandle release) {
		if (handle != null && handle.address() != 0) {
			int rc = (int) invoke(release, handle);
			if (rc != CL_SUCCESS) {
				System.getLogger("Fabric-Folia").log(System.Logger.Level.WARNING,
						"OpenCL release failed (" + errorName(rc) + ") for handle 0x"
								+ Long.toHexString(handle.address()));
			}
		}
	}

	private static void check(MemorySegment errSlot, String what) {
		check(errSlot.get(ValueLayout.JAVA_INT, 0), what);
	}

	private static void check(int rc, String what) {
		if (rc != CL_SUCCESS) {
			throw new GpuException(what + " failed: " + errorName(rc)
					+ " (0x" + Integer.toHexString(rc) + ")");
		}
	}

	private static String infoString(Api a, MemorySegment target, int param, boolean platform) {
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment sizeRef = ar.allocate(ValueLayout.JAVA_LONG);
			int rc = platform
					? (int) invoke(a.getPlatformInfo, target, param, 0L, MemorySegment.NULL, sizeRef)
					: (int) invoke(a.getDeviceInfo, target, param, 0L, MemorySegment.NULL, sizeRef);
			check(rc, platform ? "clGetPlatformInfo" : "clGetDeviceInfo(size)");
			long size = sizeRef.get(ValueLayout.JAVA_LONG, 0);
			if (size == 0) {
				return "";
			}
			MemorySegment buf = ar.allocate(size);
			rc = platform
					? (int) invoke(a.getPlatformInfo, target, param, size, buf, MemorySegment.NULL)
					: (int) invoke(a.getDeviceInfo, target, param, size, buf, MemorySegment.NULL);
			check(rc, platform ? "clGetPlatformInfo" : "clGetDeviceInfo");
			return buf.getString(0);
		}
	}

	private static long infoLong(Api a, MemorySegment target, int param, ValueLayout layout, long failureValue) {
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment buf = ar.allocate(layout);
			int rc = (int) invoke(a.getDeviceInfo, target, param, layout.byteSize(), buf, MemorySegment.NULL);
			if (rc != CL_SUCCESS) {
				// e.g. CL_DEVICE_DOUBLE_FP_CONFIG unsupported on old 1.0 devices
				return failureValue;
			}
			return layout == ValueLayout.JAVA_INT ? buf.get(ValueLayout.JAVA_INT, 0) : buf.get(ValueLayout.JAVA_LONG, 0);
		}
	}

	private static String buildLog(Api a, MemorySegment program, MemorySegment device) {
		try (Arena ar = Arena.ofConfined()) {
			MemorySegment sizeRef = ar.allocate(ValueLayout.JAVA_LONG);
			if ((int) invoke(a.getProgramBuildInfo, program, device, CL_PROGRAM_BUILD_LOG,
					0L, MemorySegment.NULL, sizeRef) != CL_SUCCESS) {
				return "<build log unavailable>";
			}
			long size = sizeRef.get(ValueLayout.JAVA_LONG, 0);
			if (size == 0) {
				return "<empty build log>";
			}
			MemorySegment buf = ar.allocate(size);
			int rc = (int) invoke(a.getProgramBuildInfo, program, device, CL_PROGRAM_BUILD_LOG,
					size, buf, MemorySegment.NULL);
			return rc == CL_SUCCESS ? buf.getString(0) : "<build log unavailable>";
		}
	}

	static String errorName(int rc) {
		return switch (rc) {
			case -1 -> "CL_DEVICE_NOT_FOUND";
			case -2 -> "CL_DEVICE_NOT_AVAILABLE";
			case -3 -> "CL_COMPILER_NOT_AVAILABLE";
			case -4 -> "CL_MEM_OBJECT_ALLOCATION_FAILURE";
			case -5 -> "CL_OUT_OF_RESOURCES";
			case -6 -> "CL_OUT_OF_HOST_MEMORY";
			case -11 -> "CL_BUILD_PROGRAM_FAILURE";
			case -12 -> "CL_MAP_FAILURE";
			case -30 -> "CL_INVALID_VALUE";
			case -32 -> "CL_INVALID_PLATFORM";
			case -33 -> "CL_INVALID_DEVICE";
			case -34 -> "CL_INVALID_CONTEXT";
			case -36 -> "CL_INVALID_COMMAND_QUEUE";
			case -38 -> "CL_INVALID_MEM_OBJECT";
			case -44 -> "CL_INVALID_PROGRAM";
			case -45 -> "CL_INVALID_PROGRAM_EXECUTABLE";
			case -48 -> "CL_INVALID_KERNEL";
			case -52 -> "CL_INVALID_KERNEL_ARGS";
			case -54 -> "CL_INVALID_WORK_GROUP_SIZE";
			case -61 -> "CL_INVALID_BUFFER_SIZE";
			default -> "OpenCL error";
		};
	}

	// ------------------------------------------------------------------
	// API table
	// ------------------------------------------------------------------

	static final class Api {
		final SymbolLookup lookup;
		final MethodHandle getPlatformIDs;
		final MethodHandle getPlatformInfo;
		final MethodHandle getDeviceIDs;
		final MethodHandle getDeviceInfo;
		final MethodHandle createContext;
		final MethodHandle createCommandQueue;
		final MethodHandle createProgramWithSource;
		final MethodHandle buildProgram;
		final MethodHandle getProgramBuildInfo;
		final MethodHandle createKernel;
		final MethodHandle createBuffer;
		final MethodHandle setKernelArg;
		final MethodHandle enqueueWriteBuffer;
		final MethodHandle enqueueReadBuffer;
		final MethodHandle enqueueNDRangeKernel;
		final MethodHandle finish;
		final MethodHandle releaseMem;
		final MethodHandle releaseKernel;
		final MethodHandle releaseProgram;
		final MethodHandle releaseCommandQueue;
		final MethodHandle releaseContext;

		Api(SymbolLookup lookup) {
			this.lookup = lookup;
			getPlatformIDs = fn("clGetPlatformIDs", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			getPlatformInfo = fn("clGetPlatformInfo", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			getDeviceIDs = fn("clGetDeviceIDs", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			getDeviceInfo = fn("clGetDeviceInfo", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			createContext = fn("clCreateContext", FunctionDescriptor.of(ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			createCommandQueue = fn("clCreateCommandQueue", FunctionDescriptor.of(ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
			createProgramWithSource = fn("clCreateProgramWithSource", FunctionDescriptor.of(ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			buildProgram = fn("clBuildProgram", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			getProgramBuildInfo = fn("clGetProgramBuildInfo", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			createKernel = fn("clCreateKernel", FunctionDescriptor.of(ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			createBuffer = fn("clCreateBuffer", FunctionDescriptor.of(ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			setKernelArg = fn("clSetKernelArg", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
			enqueueWriteBuffer = fn("clEnqueueWriteBuffer", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
					ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			enqueueReadBuffer = fn("clEnqueueReadBuffer", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
					ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			enqueueNDRangeKernel = fn("clEnqueueNDRangeKernel", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
					ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			finish = fn("clFinish", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			releaseMem = fn("clReleaseMemObject", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			releaseKernel = fn("clReleaseKernel", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			releaseProgram = fn("clReleaseProgram", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			releaseCommandQueue = fn("clReleaseCommandQueue", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			releaseContext = fn("clReleaseContext", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		}

		private MethodHandle fn(String name, FunctionDescriptor fd) {
			MemorySegment symbol = lookup.find(name)
					.orElseThrow(() -> new GpuException("OpenCL export missing from loader: " + name));
			return LINKER.downcallHandle(symbol, fd);
		}
	}
}
