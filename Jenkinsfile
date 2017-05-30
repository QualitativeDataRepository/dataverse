#!groovy

node {
  workspace = pwd()
  properties([[$class: 'ParametersDefinitionProperty', parameterDefinitions: [
    [ name: 'app',        $class: 'StringParameterDefinition', defaultValue: "dataverse" ],
    [ name: 'branch',     $class: 'StringParameterDefinition', defaultValue: "${env.JOB_BASE_NAME}" ],
    [ name: 'deployenv',  $class: 'StringParameterDefinition', defaultValue: 'dev-aws' ],
    [ name: 'deployuser', $class: 'StringParameterDefinition', defaultValue: 'jenkins' ]
  ]]])

  stage('Init') {
    /*
    * Checkout code
    */
    checkout scm
    currentBuild.result = 'SUCCESS'
    sh(script:"curl -X POST http://graphite.int.qdr.org:81/events/ -d '{\"what\": \"deploy ${app}/${branch} to ${deployenv}\", \"tags\" : \"deployment\"}'")
  }

  stage('Test') {
    /*
    * Run Unit tests
    */
    notifyBuild("Running Tests", "good")

    try {
      withMaven(
        jdk: 'jdk8',
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
        jdk: 'jdk8',
        maven: 'mvn-3-5-0') {
          sh "mvn clean package -DskipTests"
      }
    }
    catch (e) {
      currentBuild.result = "FAILURE"
      notifyBuild("Warning: Build failed!", "warning")
    }
  }
  //
  // stage('Deploy') {
  //   /*
  //   *  Require UA step when deploying from master to stage or prod
  //   */
  //   if ("${branch}" == "master") {
  //     timeout(time: 1, unit: "HOUR") {
  //       notifyBuild("Click to <$JOB_URL/workflow-stage|deploy> master", "good")
  //
  //       input message: 'Select environment', ok: 'Press to deploy',
  //         name: 'deployenv', description: 'Deploy master branch?',
  //         parameters: [choice(choices: ['dev-aws', 'stage-aws', 'prod-aws'])],
  //         submitterParameter: 'deployuser'
  //     }
  //   }

    /*
    * Deploy code
    */
    // notifyBuild("${deployuser} deploying ${app} to ${deployenv} <$BUILD_URL/console|(See Logs)>", "good")
    // try {
    //   sh(returnStdout:true, script:"./web_deploy.py -v --action deploy -e ${deployenv} --app ${app} --build_id ${env.BUILD_ID}").trim()
    // }
    // catch (e) {
    //   currentBuild.result = "FAILURE"
    //   notifyBuild("Deploying ${app} to ${deployenv} Failed! <$BUILD_URL/console|(See Logs)>", "danger")
    //   throw e
    // }
    /*
    * Start optional rollback job.
    * - Uses Jenkinsfile-rollback
    * - http://jenkins.int.qdr.org/job/Rollback
    */
    // build job: 'Rollback',
    //   parameters: [
    //     string(name: 'build_id', value: "${env.BUILD_ID}"),
    //     string(name: 'environment', value: "${deployenv}"),
    //     string(name: 'workspace', value: "${workspace}"),
    //     string(name: 'app', value: "${app}")],
    //   wait: false
  }
  //
  // stage('Smoke') {
  //   /*
  //   * Only for master branch / prod environment.
  //   */
  //   if ("${branch}" == "master" || "${branch}" == "dev") {
  //     try {
  //         notifyBuild("Smoke tests starting: ${deployenv} <$JENKINS_URL/job/Web-Tests/lastBuild/console|(See Logs)>", "good")
  //         // build job: 'web-tests', parameters: [
  //         //   string(name: 'environment', value: "${deployenv}")], wait: true
  //         build job: 'dataverse-tests',
  //           parameters: [
  //             string(name: 'environment', value: "${deployenv}")],
  //           wait: true
  //     }
  //     catch (e) {
  //       currentBuild.result = "UNSTABLE"
  //
  //       throw e
  //     }
  //     finally {
  //       build job: 'Test-Cleanup', wait: false
  //     }
  //   }
  //   echo "RESULT: ${currentBuild.result}"
  // }


}


@NonCPS
def notifyBuild(String message, String color) {
  slackSend message: "<$JOB_URL|$JOB_NAME>: ${message}", color: "${color}"
}
