import hudson.model.Run;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.checks.github.GitHubChecksPublisherFactory;

def call(Map pipelineParams) {
  scmUrl = scm.getUserRemoteConfigs()[0].getUrl()

  sonarDashboard = "/dashboard?id="
  sonarReportLink = ""
  String artifactoryLink = ""

	INFERRED_BRANCH_NAME = env.BRANCH_NAME
  REF = ""

	if (env.CHANGE_ID) {
		INFERRED_BRANCH_NAME = env.CHANGE_BRANCH
	}

    pipeline {
         environment {
             scannerHome = tool 'sonnar_scanner'
         }
         agent any
         //release/fixversion
          triggers {
                  GenericTrigger(
                    genericVariables: [
                      [key: 'fixVersions', value: '$.issue.fields.fixVersions[0].name'],
                      [key: 'buildID', value: '$.issue.fields.customfield_10700']
                    ],

                    causeString: 'Triggered on $fixVersions',

                    token: pipelineParams['repositoryName'],
                    tokenCredentialId: '',

                    printContributedVariables: true,
                    printPostContent: true,

                    silentResponse: false,

                    regexpFilterText: 'feature/$fixVersions',
                    regexpFilterExpression: INFERRED_BRANCH_NAME
                  )
                }
          stages {
            stage ('init'){
              steps {
                sh "echo $buildID"
              }
              // needs to get the jira status name for the case selector
              // Set deployment REPO_PATH
              // script {
              //     def branch_dir
              //     def branch = env.CHANGE_BRANCH ?: env.BRANCH_NAME
              //     switch (env.DEPLOYMENT) {
              //         case 'staging':
              //             env.REPO_PATH = "staging-repo"
              //             break
              //         case 'qa':
              //             env.REPO_PATH = "qa-repo"
              //             break
              //         case 'release':
              //             env.REPO_PATH = "release-repo"
              //             break
              //         default:
              //             branch_dir = 'development'
              //             env.REPO_PATH = "build-repo"
              //             break
              //     }
              // }

              //define buildID if exists
              script {
                  
              }

            }
            stage('build') {
              agent{
                docker {
                  image pipelineParams['dockerImage']
                  registryUrl pipelineParams['dockerRegistryUrl']
                  registryCredentialsId 'docker-registry'
                  reuseNode true
                }
              }
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
            // stage('Promotion'){
            //   steps{
            //     script {
            //       if (pipelineParams['fullTestAutomation'] != false)
            //         {
            //         input message: "Proceed to unit testing?"
            //         }
            //     }
            //   }
            // }
            stage('unit testing'){
              agent{
                docker {
                  image pipelineParams['dockerImage']
                  registryUrl pipelineParams['dockerRegistryUrl']
                  registryCredentialsId 'docker-registry'
                  reuseNode true
                }
              }
              steps {
                // sh"""
                //   cd ${pipelineParams['cmakeBuildDir']}/tests
                //         ctest -R unitTests
                // """

                 sh"""
                   cd ${pipelineParams['cmakeBuildDir']}/tests
                         ctest
                """
                publishChecks name: 'Unit Testing'

                junit skipPublishingChecks: true, testResults: "**/${pipelineParams['cmakeBuildDir']}/gtest-report.xml"
                //junit skipPublishingChecks: true, testResults: 'valgrind-report.xml'

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
              //agent{
              //  docker {
              //    image pipelineParams['dockerImage']
              //    registryUrl pipelineParams['dockerRegistryUrl']
              //    registryCredentialsId 'docker-registry'
              //    args "-v ${scannerHome}:${scannerHome}"
	      //	  reuseNode true
              //  }
              //}
                steps {
                  publishChecks name: 'Static Analysis',
                              text: 'testing -> manual status: in progress',
                              status: 'IN_PROGRESS'

                    //sh"""
                    // cd ${pipelineParams['cmakeBuildDir']}/tests
                    //  ctest -R "codeCoverage|cppcheckAnalysis"
                    //"""

                    //cobertura to publish the reports
                    cobertura coberturaReportFile: "**/${pipelineParams['cmakeBuildDir']}/gcovr-report.xml"

                  withSonarQubeEnv('sonarqube_airfcms') {
                    //-X is enabled to get more information in console output (jenkins)
                    sh 'env' //to see if i have the SonarHost link to use instead of writing in a variable - env.SONAR_xx check jenkinsLog
                    sh "cd ${WORKSPACE}/${pipelineParams['repositoryName']}; ${scannerHome}/bin/sonar-scanner -X -Dproject.settings=sonar-project.properties"
                    script {
                      sonarReportLink = env.SONAR_HOST_URL + sonarDashboard + pipelineParams['repositoryName']
                    }
                  }
                  timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                  }
				  publishChecks name: 'Static Analysis',
                                text: 'To view the SonarQube report please access it clicking the link below',
                                status: 'COMPLETED',
                                detailsURL: sonarReportLink

                }

            }//stage(static analysis) closed bracket
            stage('deploy') {
              steps{
                    publishChecks name: 'Deployment',
                                  text: 'testing -> manual status: in progress',
                                  status: 'IN_PROGRESS'
                    rtServer (
                        id: pipelineParams['artifactoryGenericRegistry_ID'],
                        url: "${pipelineParams['artifactoryGenericRegistry_URL']}/artifactory",
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

                    script {
                      def artifactoryRegexLink_Pattern = /^(?i).*artif.*(?<link>${pipelineParams['artifactoryGenericRegistry_URL']}.*${pipelineParams['repositoryName']}.*${env.BRANCH_NAME}.*${env.BUILD_NUMBER}.\d+.*)/
		                  def matcher = null

                      for(String line in currentBuild.getRawBuild().getLog(10)){

                  			matcher = line =~ artifactoryRegexLink_Pattern
                  			if (matcher.matches() && matcher.hasGroup())
                  			{
                  			  artifactoryLink = matcher.group("link")
                  			}
                      }
                      artifactoryLink.length() == 0 ? env.JOB_DISPLAY_URL : artifactoryLink
                    }
			  	          publishChecks name: 'Deployment',
                                  text: 'To view the artifactory please access it clicking the link below',
                                  status: 'COMPLETED',
                                  detailsURL: artifactoryLink
                }
            } //stage(deploy) closed bracket

            stage(promote){
              steps{
                rtServer (
                        id: pipelineParams['artifactoryGenericRegistry_ID'],
                        url: "${pipelineParams['artifactoryGenericRegistry_URL']}/artifactory",
                        credentialsId: 'artifact_registry'
                    )
                rtAddInteractivePromotion (
                  buildName: pipelineParams['repositoryName'] + ' :: ' + INFERRED_BRANCH_NAME,
                  buildNumber: env.BUILD_ID,
                  serverId: pipelineParams['artifactoryGenericRegistry_ID'],
                  //If set, the promotion window will display this label instead of the build name and number.
                  displayName: 'Promote me please',
                  // Name of target repository in Artifactory
                  targetRepo: 'staging-repo',
                  // Specifies the source repository for build artifacts.
                  sourceRepo: 'build-repo',
                  // Indicates whether to copy the files. Move is the default.
                  copy: true
                )
              }
            } //stage(promote) closed bracket
          } //stages body closed bracket
        } //pipeline body closed bracket
} //def body closed bracket


