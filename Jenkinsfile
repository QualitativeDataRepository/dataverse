#!groovy

node {
  workspace = pwd()
  properties([[$class: 'ParametersDefinitionProperty', parameterDefinitions:
  [ [name: 'deployedBy', $class: 'StringParameterDefinition', defaultValue: 'jenkins', description: 'deployer'],
    [name: 'environment', $class: 'StringParameterDefinition', defaultValue: 'dev-aws', description: 'env'],
    [name: 'app', $class: 'StringParameterDefinition', defaultValue: "dataverse", description: 'app'],
    [name: 'branch', $class: 'StringParameterDefinition', defaultValue: "${env.JOB_BASE_NAME}", description: 'branch'] ]
  ]])

  stage('Build') {
    /*
    * Checkout code
    */
    checkout scm
    currentBuild.result = 'SUCCESS'
    sh(script:"curl -X POST http://grafana.int.qdr.org:81/events/ -d '{\"what\": \"deploy ${app}/${branch} to ${environment}\", \"tags\" : \"deployment\"}'")
  }

  stage('Test') {
    /*
    * Run Drush Unit tests
    */
    try {
      //notifyBuild("Running unit tests", "good")
      //sh(returnStdout:true, script:"drush test-run --all").trim()
      //sh(returnStdout:true, script:"sudo -u www-data php ./scripts/run-tests.sh --url http://qdr-dev.syr.edu/ --all --color --verbose").trim()
    }
    catch (e) {
      currentBuild.result = "UNSTABLE"
      notifyBuild("Warning: Unit tests failed!", "warning")
    }
  }

  stage('Deploy') {
    /*
    *  Require UA step when deploying from master to stage or prod
    */
    if ("${branch}" == "master") {
      timeout(time: 1, unit: "HOUR") {
        notifyBuild("Click to <$JOB_URL/workflow-stage|deploy> master", "good")

        input message: 'Select environment', ok: 'Press to deploy',
          name: 'environment', description: 'Deploy master branch?',
          parameters: [choice(choices: ['dev-aws', 'stage-aws', 'prod-aws'])],
          submitterParameter: 'deployedBy'
      }
    }

    /*
    * Deploy code
    */
    // notifyBuild("${deployedBy} deploying ${app} to ${environment} <$BUILD_URL/console|(See Logs)>", "good")
    // try {
    //   sh(returnStdout:true, script:"./web_deploy.py -v --action deploy -e ${environment} --app ${app} --build_id ${env.BUILD_ID}").trim()
    // }
    // catch (e) {
    //   currentBuild.result = "FAILURE"
    //   notifyBuild("Deploying ${app} to ${environment} Failed! <$BUILD_URL/console|(See Logs)>", "danger")
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
    //     string(name: 'environment', value: "${environment}"),
    //     string(name: 'workspace', value: "${workspace}"),
    //     string(name: 'app', value: "${app}")],
    //   wait: false
  }

  stage('Smoke') {
    /*
    * Only for master branch / prod environment.
    */
    if ("${branch}" == "master" || "${branch}" == "dev") {
      try {
          notifyBuild("Smoke tests starting: ${environment} <$JENKINS_URL/job/Web-Tests/lastBuild/console|(See Logs)>", "good")
          // build job: 'web-tests', parameters: [
          //   string(name: 'environment', value: "${environment}")], wait: true
          build job: 'dataverse-tests',
            parameters: [
              string(name: 'environment', value: "${environment}")],
            wait: true
      }
      catch (e) {
        currentBuild.result = "UNSTABLE"

        throw e
      }
      finally {
        build job: 'Test-Cleanup', wait: false
      }
    }
    echo "RESULT: ${currentBuild.result}"
  }
}


@NonCPS
def notifyBuild(String message, String color) {
  slackSend message: "<$JOB_URL|$JOB_NAME>: ${message}", color: "${color}"
}
