#!groovy

node {
  workspace = pwd()
  properties([[$class: 'ParametersDefinitionProperty', parameterDefinitions: [
    [ name: 'DEPLOY_TARGET',  $class: 'StringParameterDefinition', defaultValue: 'qdr-dev' ],
  ]]])

  withCredentials([
    string(credentialsId: 'dataverse-deploy-user ', variable: 'DATAVERSE_DEPLOY_USER'),
    string(credentialsId: 'GRAPHITE_URL ', variable: 'GRAPHITE_URL'),
    ]) {

    stage('Init') {
      /*
      * Checkout code
      */
      checkout scm
      ARTIFACT_ID = readMavenPom().getArtifactId()
      VERSION = readMavenPom(file: 'modules/dataverse-parent/pom.xml').getVersion()
      currentBuild.result = 'SUCCESS'
    }

    stage('Test') {
      /*
      * Run Unit tests
      */
      notifyBuild("Running Tests", "good")

      try {
        withMaven(
          //jdk: 'jdk11',
          maven: 'mvn-3-5-0') {
            sh "mvn test"
          }
      }
      catch (e) {
        currentBuild.result = "UNSTABLE"
        notifyBuild("Warning: Tests Failed!", "warning")
      }
    }

    stage('Build') {
      /*
      * Run Unit tests
      */
      notifyBuild("Building", "good")

      try {
        withMaven(
          //jdk: 'jdk11',
          maven: 'mvn-3-5-0') {
            sh "mvn clean package -DskipTests"
        }
      }
      catch (e) {
        currentBuild.result = "FAILURE"
        notifyBuild("Warning: Build failed!", "warning")
      }

      stash includes: 'target/dataverse*.war', name: 'dataverse-war'
    }

    stage('Deploy') {
      /*
      * Deploy
      */
      timeout(time: 2, unit: "HOURS") {
        def DEPLOY_TARGET = input message: 'Deploy to', parameters: [string(defaultValue: "${DEPLOY_TARGET}", description: 'qdr-dev, qdr-stage, qdr-prod', name: 'DEPLOY_TARGET')]
      }

      notifyBuild("Deploying ${ARTIFACT_ID}-${VERSION} to ${DEPLOY_TARGET}", "good")
      unstash 'dataverse-war'
      try {
        sh """
          rsync -av target/${ARTIFACT_ID}-${VERSION}.war ${DATAVERSE_DEPLOY_USER}@${DEPLOY_TARGET}:/srv/dataverse-releases
          ssh ${DATAVERSE_DEPLOY_USER}@${DEPLOY_TARGET} "dataverse-deploy ${ARTIFACT_ID} ${VERSION}"
        """
        notifyBuild("Success", "good")
        sh "curl -sX POST ${GRAPHITE_URL}/events/ -d '{\"what\": \"${ARTIFACT_ID}-${VERSION} to ${DEPLOY_TARGET}\", \"tags\" : \"deployment\"}'"
      }
      catch (e) {
        currentBuild.result = "FAILURE"
        notifyBuild("Failed!", "danger")
        throw e
      }
    }
  }
}

@NonCPS
def notifyBuild(String message, String color) {
  slackSend message: "<$JOB_URL|$JOB_NAME>: ${message}", color: "${color}"
}
