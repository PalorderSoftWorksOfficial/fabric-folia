pipeline {
    agent any

    parameters {
        booleanParam(
            name: 'CROSS_OS',
            defaultValue: false,
            description: 'Run tests on Windows and macOS agents when available; unavailable agents are skipped after a one-minute allocation timeout.'
        )
        booleanParam(
            name: 'PRODUCTION_RELEASE',
            defaultValue: false,
            description: 'Publish only when checked-in runtime, compatibility and independent-review evidence approves this exact commit.'
        )
    }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '30'))
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Tests') {
            steps {
                sh '''
                    set -euo pipefail
                    bash ./gradlew --no-daemon clean :common:test :api:test :fabric:test --stacktrace
                '''
            }
            post {
                always {
                    junit allowEmptyResults: false, testResults: '*/build/test-results/test/TEST-*.xml'
                }
            }
        }

        stage('Cross-OS Tests') {
            when {
                expression { params.CROSS_OS == true }
            }
            steps {
                script {
                    def branches = [:]
                    ['windows', 'macos'].each { target ->
                        def labelName = target
                        branches[labelName] = {
                            try {
                                timeout(time: 1, unit: 'MINUTES') {
                                    node(labelName) {
                                        checkout scm
                                        if (isUnix()) {
                                            sh 'bash ./gradlew --no-daemon :common:test :api:test :fabric:test --stacktrace'
                                        } else {
                                            bat 'gradlew.bat --no-daemon :common:test :api:test :fabric:test --stacktrace'
                                        }
                                        junit allowEmptyResults: false, testResults: '*/build/test-results/test/TEST-*.xml'
                                    }
                                }
                            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                                def timedOut = e.causes.size() > 0 && e.causes.every { cause ->
                                    cause.getClass().getName().contains('ExceededTimeout')
                                }
                                if (!timedOut) {
                                    throw e
                                }
                                echo 'Skipping optional cross-OS tests because the requested agent was unavailable.'
                            }
                        }
                    }
                    parallel branches
                }
            }
        }

        stage('Build') {
            steps {
                sh '''
                    set -euo pipefail
                    bash ./gradlew --no-daemon build :api:javadocJar :api:sourcesJar --stacktrace
                '''
            }
        }

        stage('Verify Candidate') {
            steps {
                sh '''
                    set -euo pipefail
                    python3 scripts/verify_release.py --build-number "$BUILD_NUMBER"
                '''
            }
        }

        stage('Archive Candidate') {
            steps {
                archiveArtifacts(
                    artifacts: 'build/release-candidate/**',
                    fingerprint: true,
                    allowEmptyArchive: false
                )
            }
        }

        stage('Production Approval') {
            when {
                expression { params.PRODUCTION_RELEASE == true }
            }
            steps {
                sh '''
                    set -euo pipefail
                    python3 scripts/verify_release.py --build-number "$BUILD_NUMBER" --production-release
                '''
            }
        }

        stage('Publish Stable') {
            when {
                allOf {
                    branch 'main'
                    expression { params.PRODUCTION_RELEASE == true }
                }
            }
            steps {
                sh '''
                    set -euo pipefail
                    set +x

                    VERSION="$(sed -n 's/^mod_version=//p' gradle.properties | head -n 1)"
                    ARTIFACT="fabric/build/libs/fabric-folia-${VERSION}.jar"
                    PUBLISH_HELPER="/usr/local/sbin/fill-publish-fabric-folia"

                    test -n "$VERSION"
                    test -s "$ARTIFACT"
                    test -x "$PUBLISH_HELPER"

                    chmod 0644 "$ARTIFACT"
                    ARTIFACT_PATH="$(realpath -e "$ARTIFACT")"

                    sudo -n -u administrator -- "$PUBLISH_HELPER" fabric-folia "$VERSION" "$BUILD_NUMBER" stable "$ARTIFACT_PATH"
                '''
            }
        }
    }

    post {
        success {
            echo 'Automated candidate validation passed. Candidate validation is not production approval.'
        }
        failure {
            echo 'Fabric-Folia validation or release failed.'
        }
        always {
            echo 'Build finished.'
        }
    }
}