pipeline {
    agent any

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
