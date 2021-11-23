import hudson.model.Run;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.checks.github.GitHubChecksPublisherFactory;

def call(Map pipelineParams) {

  scmUrl = scm.getUserRemoteConfigs()[0].getUrl()
  sonarReportLink = "http://13.79.114.164:9000/dashboard?id="

	INFERRED_BRANCH_NAME = env.BRANCH_NAME

	if (env.CHANGE_ID)
  {
		INFERRED_BRANCH_NAME = env.CHANGE_BRANCH
	}

    pipeline {
        agent any
          stages {
            stage('build') {
              agent{
                docker {
                  reuseNode true
                  image pipelineParams['dockerImage']
                  registryUrl pipelineParams['dockerRegistryUrl']
                  registryCredentialsId 'docker-registry'
                }
              }
              steps {
				        publishChecks name: 'Build',
                              text: 'testing -> manual status: in progress',
                              status: 'NONE'

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
                 """
                //sh 'sleep 60' //For testing but couldn't see the changes...
				        publishChecks name: 'Build',
                              status: 'COMPLETED'
             }
            } //stage(build) closed bracket
            stage('unit testing'){
			        steps {
				        publishChecks name: 'Unit Testing'
			        }
            }
            stage('sw integration testing') {
			        steps {
				        publishChecks name: 'Integration Testing'
			        }
            }
            stage('hw/sw integration testing') {
			        steps {
			          publishChecks name: 'HW/SW Integration Testing'
			        }
            }
            stage('static analysis') {
                environment {
                  scannerHome = tool 'sonnar_scanner'
                }
                steps {
				          publishChecks name: 'Static Analysis',
                                text: 'testing -> manual status: in progress',
                                status: 'IN_PROGRESS'

                  withSonarQubeEnv('sonarqube_airfcms') {
                    //-X is enabled to get more information in console output (jenkins)
                    sh "cd ${WORKSPACE}/${pipelineParams['repositoryName']}; ${scannerHome}/bin/sonar-scanner -X -Dproject.settings=sonar-project.properties"
                    sh 'env' //to see if i have the SonarHost link to use instead of writing in a variable - env.SONAR_xx check jenkinsLog
                  }
                  timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                  }
                  //sh 'sleep 60' //For testing but couldn't see the changes...
				          publishChecks name: 'Static Analysis',
                                text: 'To view the SonarQube report please access it clicking the link below',
                                status: 'COMPLETED',
                                detailsURL: sonarReportLink + pipelineParams['repositoryName']
                }
            } //stage(static analysis) closed bracket
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

			  	      publishChecks name: 'Deployment'
              }
               if (manager.logContains('*Browse it in Artifactory*')) {
                  error("Build failed because of this and that..")
                }
            } //stage(deploy) closed bracket
          } //stages body closed bracket
        } //pipeline body closed bracket
} //def body closed bracket


