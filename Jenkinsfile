pipeline {
    agent any

    parameters {
        booleanParam(
            name: 'CROSS_OS',
            defaultValue: false,
            description: 'Also run tests on windows/macos labelled agents; unavailable agents are skipped after a 1-minute queue timeout'
        )
    }

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Test') {
            steps {
                sh '''
                    set -e

                    chmod +x ./gradlew

                    ./gradlew :common:test :fabric:test :api:test --stacktrace
                '''
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '*/build/test-results/test/*.xml'
                }
            }
        }

        stage('Cross-OS Test') {
            when {
                expression { params.CROSS_OS == true }
            }
            steps {
                script {
                    def crossOsBranches = [:]

                    ['windows', 'macos'].each { targetOs ->
                        def osLabel = targetOs
                        crossOsBranches[osLabel] = {
                            try {
                                timeout(time: 1, unit: 'MINUTES') {
                                    node(osLabel) {
                                        checkout scm
                                        try {
                                            if (isUnix()) {
                                                sh 'chmod +x ./gradlew && ./gradlew :common:test :fabric:test :api:test --stacktrace'
                                            } else {
                                                bat 'gradlew.bat :common:test :fabric:test :api:test --stacktrace'
                                            }
                                        } finally {
                                            junit allowEmptyResults: true, testResults: '*/build/test-results/test/*.xml'
                                        }
                                    }
                                }
                            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                                def timedOut = e.causes.size() > 0 && e.causes.every { cause ->
                                    cause.getClass().getName().contains('ExceededTimeout')
                                }
                                if (!timedOut) {
                                    throw e
                                }
                                echo "Skipping ${osLabel} cross-OS tests: no matching Jenkins agent became available within 1 minute."
                            }
                        }
                    }

                    parallel crossOsBranches
                }
            }
        }

        stage('Build') {
            steps {
                sh '''
                    set -e

                    chmod +x ./gradlew

                    ./gradlew build --stacktrace
                '''
            }
        }

        stage('Archive Build') {
            steps {
                archiveArtifacts(
                    artifacts: 'fabric/build/libs/*.jar',
                    fingerprint: true
                )
            }
        }

        stage('Publish to Fill') {
            when {
                branch 'main'
            }
            steps {
                sh '''
                    set -eu
                    set +x

                    VERSION="$(sed -n 's/^mod_version=//p' gradle.properties | head -n 1)"
                    if [ -z "$VERSION" ]; then
                        echo "mod_version was not found in gradle.properties"
                        exit 1
                    fi

                    ARTIFACT_COUNT=0

                    for ARTIFACT in fabric/build/libs/*.jar; do
                        [ -f "$ARTIFACT" ] || continue
                        /home/administrator/palorder-fill/jenkins-publish.sh fabric-folia "$VERSION" "$BUILD_NUMBER" stable "$ARTIFACT"
                        ARTIFACT_COUNT=$((ARTIFACT_COUNT + 1))
                    done

                    if [ "$ARTIFACT_COUNT" -eq 0 ]; then
                        echo "No publishable Fabric-Folia artifacts found"
                        exit 1
                    fi
                '''
            }
        }
    }

    post {
        success {
            echo 'Fabric-Folia build completed successfully.'
        }

        failure {
            echo 'Fabric-Folia build failed.'
        }

        always {
            echo "Build finished: ${currentBuild.currentResult}"
        }
    }
}
