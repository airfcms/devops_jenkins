import hudson.model.Run;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.checks.github.GitHubChecksPublisherFactory;

//Cleaning is needed(Testing in needed -> Env.variables) and Integration with SonnarQube

def call(body) {
    // evaluate the body block, and collect configuration into the objectdef
    pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    scmUrl = scm.getUserRemoteConfigs()[0].getUrl()
	//ChecksPublisher publisher = GitHubChecksPublisherFactory.fromRun(run);

	INFERRED_BRANCH_NAME = env.BRANCH_NAME
	
	if (env.CHANGE_ID) {
		INFERRED_BRANCH_NAME = env.CHANGE_BRANCH
	}
	
    pipeline {
        agent any
          stages {
            stage('build') {
              agent{
                docker {
                  reuseNode true //Don't see the difference on::off ### From the consoleOutput it seems the image is removed when the building stage is finished. Need to check why!!! <---------------
                  image pipelineParams['dockerImage']
                  registryUrl pipelineParams['dockerRegistryUrl']
                  registryCredentialsId 'docker-registry'
                }
              }
              steps {
                //Link can't be literally here #########
                sh 'env | sort' //To check available global variables

                //Work around because the declarative sintax bugs with deleteDir() and cleanWS()
                sh 'rm -rf ${WORKSPACE}/*'

                sh"""
                  echo Cloning Repository in Docker Image Workspace
                  git clone ${scmUrl}
                  cd ${pipelineParams['repositoryName']}
				  
                  git checkout ${INFERRED_BRANCH_NAME}
                  cd ..
                  cmake -S ${pipelineParams['repositoryName']} -B ${pipelineParams['cmakeBuildDir']}
                  make -C ${pipelineParams['cmakeBuildDir']}
                  ./${pipelineParams['cmakeBuildDir']}/${pipelineParams['repositoryName']}
                 """
				 
				 publishChecks name: 'example', title: 'Pipeline Check', summary: 'check through pipeline', text: 'you can publish checks in pipeline script', detailsURL: 'https://github.com/jenkinsci', actions: [[label:'an-user-request-action', description:'actions allow users to request pre-defined behaviours', identifier:'an unique identifier']]
             }
            } //stage(build) closed bracket
            /*
            stage('unit testing'){

            }
            stage('sw integration testing') {

            }
            stage('hw/sw integration testing') {

            }
            */
            stage('deploy') {
              steps{
                 rtServer (
                    id: pipelineParams['artifactoryGenericRegistry_ID'],
                    url: 'http://40.67.228.51:8082/artifactory',
                    credentialsId: 'artifact_registry'
                )
                rtUpload(
                      serverId: pipelineParams['artifactoryGenericRegistry_ID'],
                      spec: """{
                                "files": [
                                           {
                                            "pattern": "*/${pipelineParams['repositoryName']}",
                                            "target": "build-repo/"
                                            }
                                         ]
                                }"""
                )
                rtPublishBuildInfo (
                    serverId: pipelineParams['artifactoryGenericRegistry_ID']
                )
              }
            } //stage(deploy) closed bracket
            stage('static analysis') {
                environment {
                  scannerHome = tool 'sonnar_scanner'
                }
                steps {
                  withSonarQubeEnv('sonarqube_airfcms') {
                    //-X is enabled to get more information in console output (jenkins)
                    sh "cd ${WORKSPACE}/${pipelineParams['repositoryName']}; ${scannerHome}/bin/sonar-scanner -X -Dproject.settings=sonar-project.properties"
                  }
                  timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                  }
                }
            }
          } //stages body closed bracket
        } //pipeline body closed bracket
} //def body closed bracket


