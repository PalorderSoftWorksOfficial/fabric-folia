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


This is intentionally simple: checkout → Gradle build → archive the resulting JAR.

One thing to check on your Ubuntu machine

Because your Jenkins service runs as the jenkins user, make sure Java 25 is available to that user:

sudo -u jenkins java -version


You want:

openjdk version "25..."


Your Jenkins startup log previously showed Java 25, so this should already be fine.

Then commit the Jenkinsfile

From your development copy of fabric-folia:

git add Jenkinsfile
git commit -m "Add Jenkins CI pipeline"
git push


Then go to your Jenkins Multibranch Pipeline:

fabric-folia → Scan Multibranch Pipeline Now

Jenkins should discover the branch and find the Jenkinsfile.

The build should then execute:

Checkout
   ↓
Build
   ↓
./gradlew build --stacktrace
   ↓
Archive Build
   ↓
fabric/build/libs/*.jar


Your repository's README confirms that ./gradlew build builds all modules and runs the test suite, and identifies fabric/build/libs/fabric-0.1.0.jar as the distributable mod JAR. {"fallbackMarkdown":"(GitHub
)","reference":{"matched_text":"","prefix":null,"start_idx":2769,"end_idx":2786,"safe_urls":["https://github.com/PalorderSoftWorksOfficial/fabric-folia"],"refs":[],"alt":"(GitHub
)","prompt_text":null,"type":"grouped_webpages","error":null,"style":null,"status":"done","fallback_items":null,"items":[{"title":"GitHub - PalorderSoftWorksOfficial/fabric-folia · GitHub","url":"https://github.com/PalorderSoftWorksOfficial/fabric-folia","attribution":"GitHub","pub_date":null,"snippet":null,"thumbnail_url":"https://images.openai.com/static-rsc-1/JDEC7TN4HBh8bbgOVETEGEeAC6SpB7cYBWL4lsq4B_qoOzAFixLxopv13rGJS0hS4eBm1iEYG2ewW8WLfwFt0fgY0UUE14KYCIg1kFEH6ORTdqYvLwIZuT_mNPkwXdsOCLctqgZDCbCSgQC9LOaMsCmsmEyaAUkpR8Etz0asHQ3X36NgoRvBMd5wtzrjHZXR0hwOPRA8fFvbe_F-mwx1Jg","attribution_segments":null,"supporting_websites":[],"refs":[{"turn_index":0,"ref_type":"view","ref_index":0}],"hue":null,"attributions":null}]},"showLoginRequiredCard":false}

After you get the first successful manual build, the next thing I'd configure is the GitHub webhook, so you don't have to click "Build Now"—a push to main can automatically trigger Jenkins.{"fallbackMarkdown":"","reference":{"matched_text":" ","prefix":null,"start_idx":2987,"end_idx":2987,"safe_urls":[],"refs":[],"alt":"","prompt_text":null,"type":"sources_footnote","sources":[{"title":"GitHub - PalorderSoftWorksOfficial/fabric-folia · GitHub","url":"https://github.com/PalorderSoftWorksOfficial/fabric-folia","attribution":"GitHub"}],"has_images":false},"showLoginRequiredCard":false}
