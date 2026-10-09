pipeline {
    agent any

    parameters {
        booleanParam(
            name: 'CROSS_OS',
            defaultValue: false,
            description: 'Also run the test protocol on windows/macos labelled agents (requires agents labelled windows and macos; default builds stay on the primary agent)'
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
                beforeAgent true
                expression { params.CROSS_OS == true }
            }
            matrix {
                axes {
                    axis {
                        name 'TARGET_OS'
                        values 'windows', 'macos'
                    }
                }
                agent { label "${TARGET_OS}" }
                stages {
                    stage('Cross-OS tests') {
                        steps {
                            checkout scm
                            script {
                                if (isUnix()) {
                                    sh 'chmod +x ./gradlew && ./gradlew :common:test :fabric:test :api:test --stacktrace'
                                } else {
                                    bat 'gradlew.bat :common:test :fabric:test :api:test --stacktrace'
                                }
                            }
                        }
                        post {
                            always {
                                junit allowEmptyResults: true, testResults: '*/build/test-results/test/*.xml'
                            }
                        }
                    }
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
