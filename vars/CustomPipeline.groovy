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
                      [key: 'fixVersions', value: '$.issue.fields.fixVersions[0].name', defaultValue: '0'],
                      [key: 'buildID', value: '$.issue.fields.customfield_10700', defaultValue: '0'], //defined default value so it does not fail
                      [key: 'deployment', value: '$.issue.fields.status.name'],
                      [key: 'changelogStatus', value: '$.issue.fields.changelog.items.field'], //if status we use the below ones
                      [key: 'fromWorkflow', value: '$.issue.fields.changelog.items.fromString']
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
            stage('init') {
              steps {
                //check trigger type
                //check if buildID exists

                // version definition
                // If defined in the Jira issue and passed the trigger, it comes from the genericVatriables
                // If not, it will be calculated
                script{
                  try{
                      env.FIX_VERSIONS = fixversions //passed the trigger and was defined in the Jira issue
                  }catch(Exception e) {

                    println(">>> Fix version not defined! Might be triggered manually or by commit. Going to get it from the Branch name.")
                    
                    fixversions = (INFERRED_BRANCH_NAME =~ /^(feature\/)(.*$)/) ///^((feature|release)\/)(.*)$/
                    if (fixversions){
                      env.FIX_VERSIONS = fixVersions[0].last() //version ID from the branch name with prefix feature/
                    } else{
                      println("not a valid branch name")
                      env.FIX_VERSIONS = env.BUILD_ID //set fix version to Branch id
                    }

                  }

                }

                //verify the buildID field and set env variable
                script{

                  try{
                    if (buildID == '0') { //default
                      println(">>> BuildID not defined!!! Going to build...")
                    }
                  }catch(Exception e) {
                    println("Exception: ${e}")
                    println("BuildID not defined!!! Might be triggered manually or by commit.")
                    buildID = '0'
                  }
                  // set env variable to the value of buildID
                  env.BUILDID = buildID

                }
              
                //needs to get the jira status name for the case selector
                //Set deployment REPO_PATH
                script {
                  
                  env.REPO_PATH = "build-repo"
                  try{
                    switch (deployment) {
                        case 'staging':
                            env.REPO_PATH = "staging-repo"
                            break
                        case 'qa':
                            env.REPO_PATH = "qa-repo"
                            break
                        case 'release':
                            env.REPO_PATH = "release-repo"
                            break
                        default:
                            env.REPO_PATH = "build-repo"
                            break
                    }
                  } catch(Exception e){
                      println("Deployment type not defined!!! Either manual or git trigger. Setting default 'development' deployment")
                      def deployment = 'development'
                  }
                }

                //Set origin repo if issue changed status
                script {
                  try{
                    if (changelogStatus == "status") {
                      switch (fromWorkflow) {
                          case 'staging':
                              env.ORIG_REPO_PATH = "staging-repo"
                              break
                          case 'qa':
                              env.ORIG_REPO_PATH = "qa-repo"
                              break
                          case 'release':
                              env.ORIG_REPO_PATH = "release-repo"
                              break
                          default:
                              env.ORIG_REPO_PATH = "build-repo"
                              break
                      }
                    }
                  } catch(Exception e){
                      println("Status has not changed!!! Either manual or git trigger. Setting default 'development' deployment")
                      env.BUILDID = '0'
                  }
                }

                // Check if build exists
                script{
                  
                  def branchUrl = pipelineParams['repositoryName'] + "%20::%20"
                  def buildInfoName = pipelineParams['repositoryName'] + " :: "
                  
                  if (INFERRED_BRANCH_NAME.contains("/")){
                    branchUrl += INFERRED_BRANCH_NAME.replaceAll("/","%20::%20")
                    buildInfoName += INFERRED_BRANCH_NAME.replaceAll("/"," :: ")
                  } else{
                    branchUrl += INFERRED_BRANCH_NAME
                    buildInfoName += INFERRED_BRANCH_NAME
                  }

                  curlstr = "curl -k -X GET ${pipelineParams['artifactoryGenericRegistry_URL']}/artifactory/api/build/${branchUrl}/${env.BUILDID}"

                  def buildInfoString = sh(
                        script: curlstr,
                        returnStdout: true
                  ).trim()

                  if (buildInfoString.contains("\"number\" : \"${env.BUILDID}\"") && buildInfoString.contains("\"name\" : \"${buildInfoName}\""))
                    {
                      println("This is the correct project branch and build id ${buildInfoName} ${env.BUILDID} ")
                      env.BUILDID = '0'
                    }

                }

                sh 'env | sort'

              }

            }
            stage('build') {
              when { expression { env.BUILDID == '0' } }//skip build stage if build ID defined in Jira
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
              when { expression { env.BUILDID == '0' } }//skip build stage if build ID defined in Jira
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

            }//stage(unit testing) closed bracket
            stage('sw integration testing') {
              when { expression { env.BUILDID == '0' } }//skip build stage if build ID defined in Jira
              steps {
              publishChecks name: 'Integration Testing'
              }
            }
            stage('hw/sw integration testing') {
              when { expression { env.BUILDID == '0' } }//skip build stage if build ID defined in Jira
              steps {
                    publishChecks name: 'HW/SW Integration Testing'
              }
            }
            stage('static analysis') {
              when { expression { env.BUILDID == '0' } }//skip build stage if build ID defined in Jira
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
              when { expression { env.BUILDID == '0' } }//skip build stage if build ID defined in Jira
              
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
                                            "target": "${env.REPO_PATH}/${pipelineParams['repositoryName']}/${env.BUILD_ID}/"
                                            }
                                         ]
                                }"""
                    )
                    rtPublishBuildInfo (
                        serverId: pipelineParams['artifactoryGenericRegistry_ID']
                        //branch name
                        //docker image
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
            stage(promote) {
              when { expression { env.BUILDID != '0' } }//skip build stage if build ID defined in Jira
              steps {
                publishChecks name: 'Promoting',
                                    text: 'testing -> manual status: in progress',
                                    status: 'IN_PROGRESS'
                sh 'ls -la ../'
                rtServer (
                    id: pipelineParams['artifactoryGenericRegistry_ID'],
                    url: "${pipelineParams['artifactoryGenericRegistry_URL']}/artifactory",
                    credentialsId: 'artifact_registry'
                )
                rtDownload(
                  serverId: pipelineParams['artifactoryGenericRegistry_ID'],
                  //buildName: 'holyFrog', not necessary as the build name is the same
                  spec: """{
                        "files": [
                          {
                            "pattern": "${env.ORIG_REPO_PATH}/${pipelineParams['repositoryName']}/${env.BUILDID}/",
                            "target": "${env.BUILDID}/"
                          }
                        ]
                  }"""
                )
                sh 'ls -la'
                rtUpload(
                    serverId: pipelineParams['artifactoryGenericRegistry_ID'],
                    spec: """{
                            "files": [
                                        {
                                        "pattern": "${env.BUILDID}/*",
                                        "target": "${env.REPO_PATH}/${pipelineParams['repositoryName']}/${env.BUILD_ID}/"
                                        }
                                      ]
                            }"""
                )
                publishChecks name: 'Promoting',
                                    text: 'To view the artifactory please access it clicking the link below',
                                    status: 'COMPLETED',
                                    detailsURL: artifactoryLink
              }
            } //stage(promote) closed bracket
          } //stages body closed bracket
        } //pipeline body closed bracket
} //def body closed bracket


