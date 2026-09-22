#!/usr/bin/env python3
"""
Protocol-level MC 26.2 bot (protocol 776) — drives the REAL play path against
the dev server: login -> configuration -> play, spawn, movement across a
region boundary, block place/break, death + respawn.

Packet IDs and field layouts extracted from the mapped 26.2 jar (protocol_version
776, per version.json inside minecraft-common jar). Offline mode.

Usage: python bot.py <phase>  (phase in: smoke, move, build, respawn, all)
"""
import json
import socket
import struct
import sys
import time
import uuid
import zlib

PROTO = 776

# ---------------------------------------------------------------- packet IDs
# Registration order extracted from *Protocols classes' ProtocolInfoBuilder
# lambdas via javap (order == ID).
LOGIN_CB = {"DISCONNECT": 0x00, "HELLO": 0x01, "LOGIN_FINISHED": 0x02,
            "COMPRESSION": 0x03, "CUSTOM_QUERY": 0x04, "COOKIE_REQUEST": 0x05}
LOGIN_SB = {"HELLO": 0x00, "KEY": 0x01, "CUSTOM_QUERY_ANSWER": 0x02,
            "LOGIN_ACKNOWLEDGED": 0x03, "COOKIE_RESPONSE": 0x04}

CONFIG_CB = {"COOKIE_REQUEST": 0x00, "CUSTOM_PAYLOAD": 0x01, "DISCONNECT": 0x02,
             "FINISH_CONFIGURATION": 0x03, "KEEP_ALIVE": 0x04, "PING": 0x05,
             "RESET_CHAT": 0x06, "REGISTRY_DATA": 0x07, "RESOURCE_PACK_POP": 0x08,
             "RESOURCE_PACK_PUSH": 0x09, "STORE_COOKIE": 0x0A, "TRANSFER": 0x0B,
             "UPDATE_ENABLED_FEATURES": 0x0C, "UPDATE_TAGS": 0x0D,
             "SELECT_KNOWN_PACKS": 0x0E, "CUSTOM_REPORT_DETAILS": 0x0F,
             "SERVER_LINKS": 0x10, "CLEAR_DIALOG": 0x11, "SHOW_DIALOG": 0x12,
             "CODE_OF_CONDUCT": 0x13}
CONFIG_SB = {"CLIENT_INFORMATION": 0x00, "COOKIE_RESPONSE": 0x01,
             "CUSTOM_PAYLOAD": 0x02, "FINISH_CONFIGURATION": 0x03,
             "KEEP_ALIVE": 0x04, "PONG": 0x05, "RESOURCE_PACK": 0x06,
             "SELECT_KNOWN_PACKS": 0x07, "CUSTOM_CLICK_ACTION": 0x08,
             "ACCEPT_CODE_OF_CONDUCT": 0x09}

# Play phase: common packets are interleaved into the game registration.
# Order (1-based extraction, minus 1):
# 26.2 play clientbound table — verified against GameProtocols bytecode.
# Registration order == packet id. NOTE: 26.2 has NO bundle-delimiter id
# (verified live: SET_HELD_SLOT lands at 0x65); bundles are length-prefixed.
GAME_CB = {
    "ADD_ENTITY": 0x00, "ANIMATE": 0x01, "AWARD_STATS": 0x02,
    "BLOCK_CHANGED_ACK": 0x03, "BLOCK_DESTRUCTION": 0x04,
    "BLOCK_ENTITY_DATA": 0x05, "BLOCK_EVENT": 0x06, "BLOCK_UPDATE": 0x07,
    "BOSS_EVENT": 0x08, "CHANGE_DIFFICULTY": 0x09,
    "CHUNK_BATCH_FINISHED": 0x0A, "CHUNK_BATCH_START": 0x0B,
    "CHUNKS_BIOMES": 0x0C, "CLEAR_TITLES": 0x0D, "COMMAND_SUGGESTIONS": 0x0E,
    "COMMANDS": 0x0F, "CONTAINER_CLOSE": 0x10, "CONTAINER_SET_CONTENT": 0x11,
    "CONTAINER_SET_DATA": 0x12, "CONTAINER_SET_SLOT": 0x13, "COOLDOWN": 0x14,
    "CUSTOM_CHAT_COMPLETIONS": 0x15, "DAMAGE_EVENT": 0x16,
    "DEBUG_BLOCK_VALUE": 0x17, "DEBUG_CHUNK_VALUE": 0x18,
    "DEBUG_ENTITY_VALUE": 0x19, "DEBUG_EVENT": 0x1A, "DEBUG_SAMPLE": 0x1B,
    "DELETE_CHAT": 0x1C, "DISCONNECT": 0x1D, "DISGUISED_CHAT": 0x1E,
    "ENTITY_EVENT": 0x1F, "ENTITY_POSITION_SYNC": 0x20, "EXPLODE": 0x21,
    "FORGET_LEVEL_CHUNK": 0x22, "GAME_EVENT": 0x23, "GAME_RULE_VALUES": 0x24,
    "GAME_TEST_HIGHLIGHT_POS": 0x25, "MOUNT_SCREEN_OPEN": 0x26,
    "HURT_ANIMATION": 0x27, "INITIALIZE_BORDER": 0x28, "KEEP_ALIVE": 0x2C,  # live-pinned: 8B frame at 15.28s
    "LOGIN_WIRE": 0x22,   # pinned live: entityId + 1-byte tail
    "PLAYER_POSITION_WIRE": 0x48,  # pinned live: teleport id + vec3 doubles
    "LEVEL_CHUNK_WITH_LIGHT": 0x2A, "LEVEL_EVENT": 0x2B,
    "LIGHT_UPDATE": 0x2D, "LOGIN": 0x2E,
    "LOW_DISK_SPACE_WARNING": 0x2F, "MAP_ITEM_DATA": 0x30,
    "RESPAWN": 0x31, "MOVE_ENTITY_POS": 0x32,  # 0x31 live-pinned: arrived on CLIENT_COMMAND, entityId+dimension
    "MOVE_ENTITY_POS_ROT": 0x33, "MOVE_MINECART": 0x34, "MOVE_ENTITY_ROT": 0x35,
    "MOVE_VEHICLE": 0x36, "OPEN_BOOK": 0x37, "OPEN_SCREEN": 0x38,
    "OPEN_SIGN_EDITOR": 0x39, "PING": 0x3A, "PLACE_GHOST_RECIPE": 0x3B,
    "PLAYER_ABILITIES": 0x3C, "PLAYER_CHAT": 0x3D, "PLAYER_COMBAT_END": 0x3E,
    "PLAYER_COMBAT_ENTER": 0x3F, "PLAYER_COMBAT_KILL": 0x40,
    "PLAYER_INFO_REMOVE": 0x41, "PLAYER_INFO_UPDATE": 0x42,
    "PLAYER_LOOK_AT": 0x43, "PLAYER_ROTATION": 0x45,
    "SET_DEFAULT_SPAWN_POSITION_WIRE": 0x44,  # world-spawn broadcast; not a teleport
    "RECIPE_BOOK_ADD": 0x46, "RECIPE_BOOK_REMOVE": 0x47,
    "RECIPE_BOOK_SETTINGS": 0x48, "REMOVE_ENTITIES": 0x49,
    "REMOVE_MOB_EFFECT": 0x4A, "RESET_SCORE": 0x4B, "RESOURCE_PACK_POP": 0x4C,
    "RESOURCE_PACK_PUSH": 0x4D, "ROTATE_HEAD": 0x4F,
    "SECTION_BLOCKS_UPDATE": 0x50, "SELECT_ADVANCEMENTS_TAB": 0x51,
    "SERVER_DATA": 0x52, "SET_ACTION_BAR_TEXT": 0x53,
    "SET_BORDER_CENTER": 0x54, "SET_BORDER_LERP_SIZE": 0x55,
    "SET_BORDER_SIZE": 0x56, "SET_BORDER_WARNING_DELAY": 0x57,
    "SET_BORDER_WARNING_DISTANCE": 0x58, "SET_CAMERA": 0x59,
    "SET_CHUNK_CACHE_CENTER": 0x5A, "SET_CHUNK_CACHE_RADIUS": 0x5B,
    "SET_CURSOR_ITEM": 0x5C, "SET_DEFAULT_SPAWN_POSITION": 0x5D,
    "SET_DISPLAY_OBJECTIVE": 0x5E, "SET_ENTITY_DATA": 0x5F,
    "SET_ENTITY_LINK": 0x60, "SET_ENTITY_MOTION": 0x61, "SET_EQUIPMENT": 0x62,
    "SET_EXPERIENCE": 0x63, "SET_HEALTH": 0x7F, "SET_HELD_SLOT": 0x65,  # 0x7F live-pinned (float 20.0 shape)
    "SET_OBJECTIVE": 0x66, "SET_PASSENGERS": 0x67,
    "SET_PLAYER_INVENTORY": 0x68, "SET_PLAYER_TEAM": 0x69, "SET_SCORE": 0x6A,
    "SET_SIMULATION_DISTANCE": 0x6B, "SET_SUBTITLE_TEXT": 0x6C,
    "SET_TIME": 0x6D, "SET_TITLE_TEXT": 0x6E, "SET_TITLES_ANIMATION": 0x6F,
    "SOUND_ENTITY": 0x70, "SOUND": 0x71, "START_CONFIGURATION": 0x72,
    "STOP_SOUND": 0x73, "STORE_COOKIE": 0x74, "SYSTEM_CHAT": 0x75,
    "TAB_LIST": 0x76, "TAG_QUERY": 0x77, "TAKE_ITEM_ENTITY": 0x78,
    "TELEPORT_ENTITY": 0x79, "TEST_INSTANCE_BLOCK_STATUS": 0x7A,
    "TICKING_STATE": 0x7B, "TICKING_STEP": 0x7C, "TRANSFER": 0x7D,
    "UPDATE_ADVANCEMENTS": 0x7E, "UPDATE_ATTRIBUTES": 0x79,  # provisional: 76/238B bursts at respawn; must not shadow SET_HEALTH
    "UPDATE_MOB_EFFECT": 0x80, "UPDATE_RECIPES": 0x81, "UPDATE_TAGS": 0x82,
    "PROJECTILE_POWER": 0x83, "CUSTOM_REPORT_DETAILS": 0x84,
    "SERVER_LINKS": 0x85, "TRACKED_WAYPOINT": 0x86, "CLEAR_DIALOG": 0x87,
    "SHOW_DIALOG": 0x88,
}

# 26.2 play serverbound table — verified against GameProtocols bytecode
# 26.2 play serverbound — fully verified by server decode-error sweep
# (each ID probed on a fresh login; the server names the packet it tried
# to decode). ping_request/teleport_to_entity are easy to miss; custom_payload
# at 0x16 is silent for unknown channels (NO-ERROR in the sweep).
GAME_SB = {
    "ACCEPT_TELEPORTATION": 0x00, "ATTACK": 0x01,
    "BLOCK_ENTITY_TAG_QUERY": 0x02, "SELECT_BUNDLE_ITEM": 0x03,
    "CHANGE_DIFFICULTY": 0x04, "CHANGE_GAME_MODE": 0x05, "CHAT_ACK": 0x06,
    "CHAT_COMMAND": 0x07, "CHAT_COMMAND_SIGNED": 0x08, "CHAT": 0x09,
    "CHAT_SESSION_UPDATE": 0x0A, "CHUNK_BATCH_RECEIVED": 0x0B,
    "CLIENT_COMMAND": 0x0C, "CLIENT_TICK_END": 0x0D,
    "CLIENT_INFORMATION": 0x0E, "COMMAND_SUGGESTION": 0x0F,
    "CONFIGURATION_ACKNOWLEDGED": 0x10, "CONTAINER_BUTTON_CLICK": 0x11,
    "CONTAINER_CLICK": 0x12, "CONTAINER_CLOSE": 0x13,
    "CONTAINER_SLOT_STATE_CHANGED": 0x14, "COOKIE_RESPONSE": 0x15,
    "CUSTOM_PAYLOAD": 0x16, "DEBUG_SUBSCRIPTION_REQUEST": 0x17,
    "EDIT_BOOK": 0x18, "ENTITY_TAG_QUERY": 0x19, "INTERACT": 0x1A,
    "JIGSAW_GENERATE": 0x1B, "LOCK_DIFFICULTY": 0x1D,
    "KEEP_ALIVE": 0x1C, "MOVE_PLAYER_POS": 0x1E,
    "MOVE_PLAYER_POS_ROT": 0x1F, "MOVE_PLAYER_ROT": 0x20,
    "MOVE_PLAYER_STATUS_ONLY": 0x21, "MOVE_VEHICLE": 0x22,
    "PADDLE_BOAT": 0x23, "PICK_ITEM_FROM_BLOCK": 0x24,
    "PICK_ITEM_FROM_ENTITY": 0x25, "PING_REQUEST": 0x26,
    "PLACE_RECIPE": 0x27, "PLAYER_ABILITIES": 0x28, "PLAYER_ACTION": 0x29,
    "PLAYER_COMMAND": 0x2A, "PLAYER_INPUT": 0x2B, "PLAYER_LOADED": 0x2C,
    "PONG": 0x2D, "RECIPE_BOOK_CHANGE_SETTINGS": 0x2E,
    "RECIPE_BOOK_SEEN_RECIPE": 0x2F, "RENAME_ITEM": 0x30,
    "RESOURCE_PACK": 0x31, "SEEN_ADVANCEMENTS": 0x32, "SELECT_TRADE": 0x33,
    "SET_BEACON": 0x34, "SET_CARRIED_ITEM": 0x35,
    "SET_COMMAND_BLOCK": 0x36, "SET_COMMAND_MINECART": 0x37,
    "SET_CREATIVE_MODE_SLOT": 0x38, "SET_GAME_RULE": 0x39,
    "SET_JIGSAW_BLOCK": 0x3A, "SET_STRUCTURE_BLOCK": 0x3B,
    "SET_TEST_BLOCK": 0x3C, "SIGN_UPDATE": 0x3D, "SPECTATOR_ACTION": 0x3E,
    "SWING": 0x3F, "TELEPORT_TO_ENTITY": 0x40,
    "TEST_INSTANCE_BLOCK_ACTION": 0x41, "USE_ITEM_ON": 0x42,
    "USE_ITEM": 0x43, "CUSTOM_CLICK_ACTION": 0x44,
}

# ---------------------------------------------------------------- primitives
def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            out += bytes([b])
            return out


def read_varint(buf, pos):
    val = 0
    for i in range(5):
        b = buf[pos + i]
        val |= (b & 0x7F) << (7 * i)
        if not (b & 0x80):
            return val, pos + i + 1
    raise ValueError("varint too long")


def _str(s):
    b = s.encode("utf-8")
    return varint(len(b)) + b


def _bool(b):
    return b"\x01" if b else b"\x00"


def _u8(b):
    return bytes([b])


def _i32(v):
    return struct.pack(">i", v)


def _f32(v):
    return struct.pack(">f", v)


def _f64(v):
    return struct.pack(">d", v)


def _u64(v):
    return struct.pack(">Q", v)


def _uuid(u=None):
    return uuid.UUID(int=u if isinstance(u, int) else uuid.uuid4().int).bytes


def _pos(x, y, z):
    # BlockPos.asLong: y bits 0..11, z bits 12..37, x bits 38..63 (26/12/26)
    v = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)
    return struct.pack(">q", v)


def blockpos_from_long(v):
    x = v >> 38
    y = (v << 52) >> 52
    z = (v << 26) >> 38
    # sign-extend
    if x >= 1 << 25: x -= 1 << 26
    if y >= 1 << 11: y -= 1 << 12
    if z >= 1 << 25: z -= 1 << 26
    return x, y, z


class Bot:
    def __init__(self, host="127.0.0.1", port=25901, name="ProbeBot"):
        self.sock = socket.create_connection((host, port), timeout=30)
        self.name = name
        self.uuid = uuid.uuid5(uuid.NAMESPACE_DNS, name)
        self.compress_threshold = -1
        self.state = "handshake"
        self.entity_id = None
        self.pos = None
        self.rot = None
        self.teleport_id = None
        self.ran_commands = set()
        self.on_ground_seq = 0
        self.blocks_placed = 0
        self.blocks_broken = 0
        self.respawned = False
        self.saw_death = False
        self.player_loaded_sent = False
        self.packets_seen = 0
        self.last_keepalive = None
        self.log = []

    # ---------------------------------------------------------- transport
    def _send_raw(self, payload):
        frame = varint(len(payload)) + payload
        if self.state != "play":
            self.log.append(f"wire {frame.hex()}")
        self.sock.sendall(frame)

    def send(self, packet_id, body=b""):
        inner = varint(packet_id) + body
        if self.compress_threshold >= 0:
            # 26.2 CompressionDecoder semantics (verified against bytecode):
            # size==0 -> uncompressed remainder; size>=threshold -> compressed.
            # (Unlike older protocol versions, there is NO "raw preamble=actual
            # length" mode for small packets — small packets carry size 0.)
            if len(inner) >= self.compress_threshold:
                comp = zlib.compress(inner)
                data = varint(len(inner)) + comp
            else:
                data = varint(0) + inner
            # _send_raw adds the outer length varint — do NOT wrap here too
            self._send_raw(data)
        else:
            self._send_raw(inner)
        if self.state != "play":
            self.log.append(f"send id={packet_id:#x} state={self.state} thr={self.compress_threshold}")

    def recv_packet(self, timeout=15):
        self.sock.settimeout(timeout)
        # length varint
        n = 0
        shift = 0
        while True:
            b = self._recvn(1)[0]
            n |= (b & 0x7F) << shift
            if not (b & 0x80):
                break
            shift += 7
        data = self._recvn(n)
        if self.compress_threshold >= 0:
            dlen, pos = read_varint(data, 0)
            body = data[pos:]
            if dlen:
                body = zlib.decompress(body)
            return body
        return data

    def _recvn(self, n):
        out = b""
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                raise ConnectionError("socket closed")
            out += chunk
        return out

    # ---------------------------------------------------------- sequencing
    def handshake(self, intent=2):
        port = self.sock.getpeername()[1]
        body = varint(PROTO) + _str("127.0.0.1") + struct.pack(">H", port) + varint(intent)
        self.send(0x00, body)  # handshake state has no compression
        self.state = "login"

    def login(self):
        # hello: name (utf max 16) + profile uuid (no optional marker —
        # verified against ServerboundHelloPacket's decode: readUtf, readUUID)
        self.send(LOGIN_SB["HELLO"], _str(self.name) + _uuid(self.uuid))
        while True:
            pkt = self.recv_packet()
            self.log.append(f"login recv {len(pkt)}B: {pkt[:24].hex()}")
            pid, pos = read_varint(pkt, 0)
            if pid == LOGIN_CB["HELLO"]:
                raise RuntimeError("server wants encryption but is offline-mode?")
            elif pid == LOGIN_CB["COMPRESSION"]:
                thr, _ = read_varint(pkt, pos)
                self.compress_threshold = thr
                self.log.append(f"compression threshold {thr}")
            elif pid == LOGIN_CB["LOGIN_FINISHED"]:
                self.log.append("login finished")
                self.send(LOGIN_SB["LOGIN_ACKNOWLEDGED"])
                self.state = "configuration"
                return
            elif pid == LOGIN_CB["DISCONNECT"]:
                raise RuntimeError("login disconnect: " + pkt[pos:].decode("utf-8", "replace"))
            elif pid == LOGIN_CB["CUSTOM_QUERY"]:
                # msg id varint + channel string + payload; answer empty
                mid, pos2 = read_varint(pkt, pos)
                self.send(LOGIN_SB["CUSTOM_QUERY_ANSWER"], varint(mid) + _bool(False))
            elif pid == LOGIN_CB["COOKIE_REQUEST"]:
                pass
            else:
                self.log.append(f"login cb id {pid:#x} ignored")

    def configure(self):
        sent_info = False
        sent_packs = False
        while True:
            pkt = self.recv_packet()
            pid, pos = read_varint(pkt, 0)
            if pid == CONFIG_CB["SELECT_KNOWN_PACKS"]:
                # respond with empty known packs list
                self.send(CONFIG_SB["SELECT_KNOWN_PACKS"], varint(0))
                sent_packs = True
            elif pid == CONFIG_CB["REGISTRY_DATA"]:
                # large; drain only
                self.log.append("registry data received")
            elif pid == CONFIG_CB["UPDATE_TAGS"]:
                pass
            elif pid == CONFIG_CB["KEEP_ALIVE"]:
                self.send(CONFIG_SB["KEEP_ALIVE"], pkt[pos:])
            elif pid == CONFIG_CB["PING"]:
                self.send(CONFIG_SB["PONG"], pkt[pos:])
            elif pid == CONFIG_CB["RESOURCE_PACK_PUSH"]:
                self.send(CONFIG_SB["RESOURCE_PACK"], _uuid(0) + _bool(True))
            elif pid == CONFIG_CB["FINISH_CONFIGURATION"]:
                if not sent_info:
                    self._send_client_info()
                    sent_info = True
                self.send(CONFIG_SB["FINISH_CONFIGURATION"])
                self.state = "play"
                self.log.append("configuration finished -> PLAY")
                return
            elif pid == CONFIG_CB["DISCONNECT"]:
                raise RuntimeError("config disconnect: " + pkt[pos:].decode("utf-8", "replace"))
            elif pid == CONFIG_CB["CODE_OF_CONDUCT"]:
                self.send(CONFIG_SB["ACCEPT_CODE_OF_CONDUCT"])
            else:
                self.log.append(f"config cb id {pid:#x} ignored")

    def _send_client_info(self):
        # ClientInformation: locale, viewDistance(byte), chatVisibility(enum),
        # chatColors(bool), modelCustomisation(ubyte), mainHand(enum),
        # textFiltering(bool), allowServerListings(bool), particleStatus(enum)
        body = (_str("en_US") + _u8(12) + varint(0) + _bool(True)
                + _u8(0) + varint(1) + _bool(False) + _bool(True) + varint(0))
        self.send(CONFIG_SB["CLIENT_INFORMATION"], body)

    # ------------------------------------------------------------ play
    def play_loop(self, seconds, act=None):
        end = time.time() + seconds
        while time.time() < end:
            self.sock.settimeout(1.0)
            try:
                pkt = self.recv_packet()
            except socket.timeout:
                self._tick()
                if act:
                    act(self)
                continue
            self.handle_play(pkt)
            self._tick()

    def handle_play(self, pkt):
        pid, pos = read_varint(pkt, 0)
        if self.packets_seen < 400:
            self.packets_seen += 1
            self.log.append(f"play pid=0x{pid:02X} len={len(pkt) - pos} head={pkt[pos:pos + 8].hex()}")
        if pid == GAME_CB["KEEP_ALIVE"]:
            self.last_keepalive = pkt[pos:]
            self.send(GAME_SB["KEEP_ALIVE"], pkt[pos:])
        elif pid == GAME_CB["PING"]:
            self.send(GAME_SB["PONG"], pkt[pos:])
        elif pid == GAME_CB["LOGIN_WIRE"]:
            self.entity_id = struct.unpack(">i", pkt[pos:pos + 4])[0]
            self.log.append(f"login packet: entity id {self.entity_id}")
        elif pid == GAME_CB["PLAYER_POSITION_WIRE"]:
            self._handle_teleport(pkt, pos)
        elif pid == GAME_CB["SET_DEFAULT_SPAWN_POSITION_WIRE"]:
            pass  # world-spawn broadcast: parsed as teleport by mistake before
        elif pid == GAME_CB["SET_HEALTH"]:
            hp = struct.unpack(">f", pkt[pos:pos + 4])[0]
            if hp <= 0.0:
                self.saw_death = True
                self.log.append("health 0 — dead")
        elif pid == GAME_CB["RESPAWN"]:
            self.respawned = True
            self.log.append("respawn received")
            self.send(GAME_SB["PLAYER_LOADED"])  # 26.2: re-arm load handshake
        elif pid == GAME_CB["DISCONNECT"]:
            raise RuntimeError("play disconnect: " + pkt[pos:].decode("utf-8", "replace"))
        elif pid in (GAME_CB["PLAYER_CHAT"], GAME_CB["DISGUISED_CHAT"]):
            pass
        elif pid == GAME_CB["LEVEL_CHUNK_WITH_LIGHT"]:
            self.log.append("chunk data")
        # everything else: drained silently

    def _handle_teleport(self, pkt, pos):
        self._tp_count = getattr(self, "_tp_count", 0) + 1
        peek = pos
        tid, peek = read_varint(pkt, peek)
        try:
            px, py, pz = struct.unpack(">ddd", pkt[peek:peek + 24])
        except struct.error:
            px = py = pz = -1
        print(f"[dbg] teleport #{self._tp_count} id={tid} pos=({px:.2f},{py:.2f},{pz:.2f})", flush=True)
        self.teleport_id, pos = read_varint(pkt, pos)
        # PMR: vec3 pos, vec3 delta, float yRot, float xRot
        x, y, z = struct.unpack(">ddd", pkt[pos:pos + 24]); pos += 24
        dx, dy, dz = struct.unpack(">ddd", pkt[pos:pos + 24]); pos += 24
        yrot, xrot = struct.unpack(">ff", pkt[pos:pos + 8]); pos += 8
        if pos < len(pkt):
            rel, pos = read_varint(pkt, pos)
        else:
            rel = 0
        self.pos = (x, y, z)
        self.rot = (yrot, xrot)
        self.send(GAME_SB["ACCEPT_TELEPORTATION"], varint(self.teleport_id))
        # confirm with a pos-rot move so the server pins us here
        self.send_move(self.pos, self.rot)
        if not self.player_loaded_sent:
            # 26.2 load handshake: server holds the player tick until this lands
            self.player_loaded_sent = True
            self.send(GAME_SB["PLAYER_LOADED"])
            self.log.append("player loaded sent")

    def send_move(self, p, r):
        self._mv_count = getattr(self, "_mv_count", 0) + 1
        x, y, z = p
        yrot, xrot = r
        body = (_f64(x) + _f64(y) + _f64(z) + _f32(yrot) + _f32(xrot)
                + _u8(0x01))  # on ground
        self.send(GAME_SB["MOVE_PLAYER_POS_ROT"], body)
        # vanilla movement is client-authoritative: server never echoes moves,
        # so dead-reckon position to keep walk_to's distance math honest.
        self.pos = (x, y, z)
        self.rot = (yrot, xrot)

    def send_chat_command(self, cmd):
        # 26.2 chat_command: command string ONLY (verified: ServerboundChatCommandPacket.write = writeUtf only)
        self.send(GAME_SB["CHAT_COMMAND"], _str(cmd))

    def _tick(self):
        self.on_ground_seq += 1
        self.send(GAME_SB["CLIENT_TICK_END"])

    # ------------------------------------------------------------ scenarios
    def walk_to(self, tx, tz, step=0.22, max_seconds=90):
        """Client-side movement toward (tx, tz) — drives chunk loading and
        region boundary crossing through the real movement path."""
        end = time.time() + max_seconds
        last_report = 0.0
        while time.time() < end:
            if time.time() - last_report > 10.0:
                last_report = time.time()
                print(f"[walk] pos={self.pos} target=({tx},{tz})")
            if self.pos is None:
                self.play_loop(0.5)
                continue
            x, y, z = self.pos
            dx, dz = tx - x, tz - z
            dist = (dx * dx + dz * dz) ** 0.5
            if dist < 1.0:
                return True
            nx = x + (dx / dist) * step
            nz = z + (dz / dist) * step
            self.send_move((nx, y, nz), self.rot or (0.0, 0.0))
            self.play_loop(0.05)
        return False

    def place_block(self, bx, by, bz):
        """USE_ITEM_ON with the given target block; place against its top."""
        # 26.2 codec order (bytecode-verified): hand, blockHit, sequence.
        # sequence: rolling counter (server echoes it in BLOCK_CHANGED_ACK)
        self.on_ground_seq += 1
        body = (varint(0)                                # hand MAIN_HAND
                + _pos(bx, by, bz) + varint(1)           # hit block, face UP
                + _f32(0.5) + _f32(1.0) + _f32(0.5)      # cursor in face
                + _bool(False) + _bool(False)            # insideBlock, worldBorder
                + varint(self.on_ground_seq))            # sequence
        self.send(GAME_SB["USE_ITEM_ON"], body)
        self.blocks_placed += 1

    def break_block(self, bx, by, bz):
        self.on_ground_seq += 1
        body = (varint(0)  # START_DESTROY_BLOCK
                + _pos(bx, by, bz)
                + _u8(1)     # face UP
                + varint(self.on_ground_seq))
        self.send(GAME_SB["PLAYER_ACTION"], body)
        self.on_ground_seq += 1
        body = (varint(2)  # STOP_DESTROY_BLOCK
                + _pos(bx, by, bz)
                + _u8(1)
                + varint(self.on_ground_seq))
        self.send(GAME_SB["PLAYER_ACTION"], body)
        self.blocks_broken += 1

    def kill_self(self):
        self.send_chat_command("kill @s")

    def respawn(self):
        # client command: enum PERFORM_RESPAWN (id 0)
        self.send(GAME_SB["CLIENT_COMMAND"], varint(0))


def main():
    phase = sys.argv[1] if len(sys.argv) > 1 else "all"
    bot = Bot(name="ProbeBot")
    try:
        print("[bot] handshake + login...")
        bot.handshake()
        bot.login()
        print("[bot] configuration...")
        bot.configure()
        print("[bot] PLAY phase")
        # initial spawn: wait for login + teleport
        bot.play_loop(4)
        print("[bot] spawned at", bot.pos, "entity", bot.entity_id)

        if phase in ("move", "all"):
            print("[bot] walking 64 blocks +Z (crosses region boundary)...")
            ok = bot.walk_to(bot.pos[0], bot.pos[2] + 64)
            print("[bot] walk done:", ok, "pos", bot.pos,
                  f"(moves={getattr(bot, '_mv_count', 0)}, teleports={getattr(bot, '_tp_count', 0)})")

        if phase in ("build", "all"):
            bx, by, bz = int(bot.pos[0]) + 1, int(bot.pos[1]) + 2, int(bot.pos[2]) + 1
            print(f"[bot] block place/break at {bx},{by},{bz}")
            bot.place_block(bx, by, bz)
            bot.play_loop(1.0)
            bot.break_block(bx, by, bz)
            bot.play_loop(1.0)
            print("[bot] build phase done; placed", bot.blocks_placed, "broke", bot.blocks_broken)

        if phase in ("respawn", "all"):
            print("[bot] dying via /kill and respawning...")
            bot.kill_self()
            bot.play_loop(3)
            print("[bot] death handled; respawning...")
            bot.respawn()
            bot.play_loop(5)
            print("[bot] respawned:", bot.respawned, "pos", bot.pos)

        bot.play_loop(2)
        print("[bot] FINAL pos", bot.pos)
        print("[bot] session complete")
    except (Exception, KeyboardInterrupt) as e:
        print(f"[bot] ABORT: {type(e).__name__}: {e}")
        raise
    finally:
        print("[bot] log:")
        for line in bot.log:
            print("  ", line)
        try:
            bot.sock.close()
        except OSError:
            pass


if __name__ == "__main__":
    main()
