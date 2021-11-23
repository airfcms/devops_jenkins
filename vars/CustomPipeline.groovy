import hudson.model.Run;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.checks.github.GitHubChecksPublisherFactory;

def call(Map pipelineParams) {
   scmUrl = scm.getUserRemoteConfigs()[0].getUrl()

	INFERRED_BRANCH_NAME = env.BRANCH_NAME

	if (env.CHANGE_ID) {
		INFERRED_BRANCH_NAME = env.CHANGE_BRANCH
	}

    pipeline {
         agent{
                docker {
                  reuseNode true
                  image pipelineParams['dockerImage']
                  registryUrl pipelineParams['dockerRegistryUrl']
                  registryCredentialsId 'docker-registry'
                }
          }
          stages {
            stage('build') {
              steps {
                publishChecks name: 'Build',
                              text: 'testing -> manual status: in progress',
                              status: 'IN_PROGRESS'
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
                 """

				        publishChecks name: 'Build',
                              status: 'COMPLETED'
              }
            } //stage(build) closed bracket
            stage('unit testing'){
			  steps {
				sh"""
				   cd ${pipelineParams['cmakeBuildDir']}/tests
			           ctest -R unitTests
				"""
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
		  sh"""
			ctest -R "codeCoverage|cppcheckAnalysis"
		  """
                  withSonarQubeEnv('sonarqube_airfcms') {
                    //-X is enabled to get more information in console output (jenkins)
                    sh "cd ${WORKSPACE}/${pipelineParams['repositoryName']}; ${scannerHome}/bin/sonar-scanner -X -Dproject.settings=sonar-project.properties"
                  }
                  timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                  }
				  publishChecks name: 'Static Analysis',
                                text: 'To view the SonarQube report please access it clicking the link below',
                                status: 'COMPLETED',
                                detailsURL: sonarReportLink + pipelineParams['repositoryName']
                }
            }//stage(static analysis) closed bracket
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
            } //stage(deploy) closed bracket
          } //stages body closed bracket
        } //pipeline body closed bracket
} //def body closed bracket


