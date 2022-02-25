import hudson.model.Run;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.checks.github.GitHubChecksPublisherFactory;

@NonCPS
String regexParser(String stringToCheck, String regexCompare) {
  /*
  Method to get the fix version from the branch name
  reference: https://www.javaallin.com/code/jenkins-groovy-regex-match-string-error-java-io-notserializableexception-jav.html
  */
  def matches = (stringToCheck =~ regexCompare)
  result = ""+matches[0].last()
  return result
}

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
                      [key: 'issueKey', value: '$.issue.key'],
                      [key: 'fixVersions', value: '$.issue.fields.fixVersions[0].name', defaultValue: '0'],
                      [key: 'buildID', value: '$.issue.fields.customfield_10700', defaultValue: '0'], //defined default value so it does not fail
                      [key: 'deployment', value: '$.issue.fields.status.name'],
                      [key: 'changelogStatus', value: '$.changelog.items[0].field', defaultValue: '0'], //if status we use the below ones
                      [key: 'fromWorkflow', value: '$.changelog.items[0].fromString'],
                      [key: 'deploymentStatus', value: '$.issue.fields.customfield_11100'],
                      [key: 'releaseVersion', value: '$.version.name', defaultValue: '0'], //From here, parameters related to release
                      [key: 'released', value: '$.version.released'], //With this we evaluate if the pipeline is to run
                      [key: 'projectID', value: '$.version.projectId'], //Need this so we can create an issue if necessary
                      [key: 'releaseVersionID', value: '$.version.id']
                    ],

                    causeString: 'Triggered on $fixVersions',

                    token: pipelineParams['repositoryName'],
                    tokenCredentialId: '',

                    printContributedVariables: true,
                    printPostContent: true,

                    silentResponse: false,

                    //regexpFilterText: 'feature/$fixVersions;$changelogStatus;$deploymentStatus',
                    //regexpFilterExpression: INFERRED_BRANCH_NAME+';status;(?!.*Deployment Failed).*'
                    //regexpFilterText: 'feature/$fixVersions;$changelogStatus;$deploymentStatus;feature/$releaseVersion;$released',
                    //regexpFilterExpression: '['+INFERRED_BRANCH_NAME+';status;(?!.*Deployment Failed).*;;|;;;'+INFERRED_BRANCH_NAME+';true]'
                    regexpFilterText: 'feature/$fixVersions;$changelogStatus;$deploymentStatus;feature/$releaseVersion;$released',
                    regexpFilterExpression: '('+INFERRED_BRANCH_NAME+'|feature/0);(status|0);(?!.*Deployment Failed).*;('+INFERRED_BRANCH_NAME+'|feature/0);(?!false).*'
                    
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
                    if (fixVersions != '0'){
                      env.FIX_VERSIONS = fixVersions //passed the trigger and was defined in the Jira issue
                    } else if (releaseVersion != '0'){
                      env.FIX_VERSIONS = releaseVersion //passed the trigger and was defined in the Jira Release
                    } else {
                      println(">>> Fix/Release version not defined! Might be triggered manually or by commit. Going to get it from the Branch name.")
                      env.FIX_VERSIONS = regexParser(INFERRED_BRANCH_NAME, /^(feature\/)(.*)$/) ///^((feature|release)\/)(.*)$/ ; version ID from the branch name with prefix feature/
                    }
                  }catch(Exception e) {
                      env.FIX_VERSIONS = '0'
                    }
                }

                //Set issue key if exists
                script{

                  try{
                    currentBuild.description = issueKey
                    env.ISSUE_KEY = issueKey
                  }catch(Exception e) {
                    println("Exception: ${e}")
                    println("No Issue Key defined! Either manual or git trigger.")
                  }

                }

                //verify the buildID field and set env variable
                script{

                  try{
                    env.BUILDID = buildID //sets the env build id to o or the actual buildID
                  }catch(Exception e) {
                    println("Exception: ${e}")
                    println("BuildID not defined!!! Might be triggered manually or by commit.")
                    env.BUILDID = '0'
                  }

                }
              
                //needs to get the jira status name for the case selector
                //Set deployment REPO_PATH
                script {
                  
                  env.REPO_PATH = "build-repo"
                  try{
                    switch (deployment) {
                        case 'Staging':
                            env.REPO_PATH = "staging-repo"
                            break
                        case 'Testing':
                            env.REPO_PATH = "qa-repo"
                            break
                        case 'Release':
                            env.REPO_PATH = "release-repo"
                            break
                        default:
                            env.REPO_PATH = "build-repo"
                            break
                    }
                  } catch(Exception e){
                      println("Deployment type not defined! Either manual or git trigger. Setting default 'development' deployment")
                      if (INFERRED_BRANCH_NAME == 'main'){
                        env.REPO_PATH = "release-repo" // Should only be here if there was a merge; might need to block direct commits to Main
                      }else{
                        env.REPO_PATH = "build-repo"
                      }
                  }
                }

                //Set origin repo if issue changed status
                script {
                  try{
                    if (changelogStatus == "status") {
                      switch (fromWorkflow) {
                          case 'Staging':
                              env.ORIG_REPO_PATH = "staging-repo"
                              env.ORIG_STATUS = "Staging"
                              break
                          case 'Testing':
                              env.ORIG_REPO_PATH = "qa-repo"
                              env.ORIG_STATUS = "Testing"
                              break
                          case 'Release':
                              env.ORIG_REPO_PATH = "release-repo"
                              env.ORIG_STATUS = "Release"
                              break
                          default:
                              env.ORIG_REPO_PATH = "build-repo"
                              env.ORIG_STATUS = "Not Deployed"
                              break
                      }
                    } else {
                      println("not a status change: "+changelogStatus)
                      env.BUILDID = '0'
                    }
                  } catch(Exception e){
                      println(e)
                      println("Status has not changed!!! Either manual or git trigger. Setting default 'development' deployment")
                      env.BUILDID = '0'
                  }
                }

                //needs to get the jira status name for the case selector
                //Set deployment REPO_PATH
                script {
                  
                  try{
                    if (released == 'true'){
                      env.BUILDID = '0'
                    }
                  } catch(Exception e){
                      println(e)
                      println("Status has not changed!!! Either manual or git trigger. Setting default 'development' deployment")
                      released = 'false' //just to make sure it does not merge
                      env.BUILDID = '0'
                  }
                }

                // Check if build exists when using deployment issue type strategy
                script{
                  if(env.BUILDID > '0'){
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

                    //check if artifact exists in the repo
                    curlstr = "curl -I ${pipelineParams['artifactoryGenericRegistry_URL']}/artifactory/${env.ORIG_REPO_PATH}/${pipelineParams['repositoryName']}/${env.BUILDID}/${pipelineParams['repositoryName']}"

                    def artifactInfoString = sh(
                          script: curlstr,
                          returnStdout: true
                    ).trim()

                    //if build exists and the artifact is in the correct repo, we proceed; Otherwise, we will force a new full build
                    if (buildInfoString.contains("\"number\" : \"${env.BUILDID}\"") && buildInfoString.contains("\"name\" : \"${buildInfoName}\"") && artifactInfoString.contains("200 OK"))
                      {
                        println("This is the correct project branch and build id ${buildInfoName} ${env.BUILDID} ")
                      }else {
                        println("Not the correct project branch or build id. Will rebuild the it...")
                        env.BUILDID = '0'
                      }
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

                //Get version from commit; not possible to get on the Init Stage
                script{
                  try{
                    if (INFERRED_BRANCH_NAME == "main"){
                          //needs to get the previous branch
                          def commitBranches = sh(
                          script: 'git show -s --format=%D $env.GIT_COMMIT',
                          returnStdout: true
                          )
                          env.FIX_VERSIONS = regexParser(regexParser(commitBranches, /^.*,\s(.+)$/), /^(feature\/)(.*)$/)
                        } else {
                          println("Manual build...")
                        }
                  }catch(Exception e){
                    println("Valid Release Branch not found!!! Either manual or git trigger (direct commit to main).")
                  }
                }

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
            stage(merge){
              when { expression { env.BUILDID == '0' && released == 'true' && currentBuild.getCurrentResult() == 'SUCCESS'} }//perform the merge if the released is true and a build was Done
              steps{
                publishChecks name: 'Merge to Master',
                              text: 'Merging -> manual status: in progress',
                              status: 'IN_PROGRESS'

                sh"""
                  echo Checking out Main Branch in Docker Image Workspace
                  cd ${pipelineParams['repositoryName']}
                  git checkout main
                  git pull
                  git merge ${INFERRED_BRANCH_NAME}
                  git push
                 """

				        publishChecks name: 'Merge to Master',
                              status: 'COMPLETED'
              }
              //post action to create issue and revert the state
              post{
                failure {
                  // def project = jiraGetProject(
                  //   idOrKey: "${projectID}"
                  // ).data.toDtring()

                  jiraNewIssue(
                    issue: [fields: [ // id or key must present for project.
                              project: [id: "${projectID}"],
                              summary: "Release Build $env.BUILD_ID has failed",
                              description: "\${BUILD_LOG, maxLines=50, escapeHtml=false}",
                              // id or name must present for issueType.
                              issuetype: [id: '3']]]
                  )

                  //revert to unreleased

                  jiraEditVersion(
                    id: "${releaseVersionID}",
                    version: [ id: "${releaseVersionID}", // need to change this to get the correct id from the version name
                      name: "${env.FIX_VERSIONS}",
                      archived: false,
                      released: false,
                      project: "${projectID}" ]
                  )
                }
              }
            }//stage(merge) closed bracket
            stage('deploy') {
              when { expression { env.BUILDID == '0' } }//skip build stage if build ID defined in Jira
              
              steps{

                    publishChecks name: 'Deployment',
                                  text: 'Deploying -> manual status: in progress',
                                  status: 'IN_PROGRESS'

                    rtServer (
                        id: pipelineParams['artifactoryGenericRegistry_ID'],
                        url: "${pipelineParams['artifactoryGenericRegistry_URL']}/artifactory",
                        credentialsId: 'artifact_registry'
                    )

                    rtBuildInfo (
                        captureEnv: true,
                        includeEnvPatterns: ['*BUILD_*', '*DOCKER_*'],
                        excludeEnvPatterns: ['*private*', 'internal-*'],
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
                //post action to create issue and revert the state
                // post{
                //   failure {
                //     // def project = jiraGetProject(
                //     //   idOrKey: "${projectID}" //might know it from the commit merge message?
                //     // ).data.toDtring()
                    
                //     def issueDescription = "\${BUILD_LOG, maxLines=50, escapeHtml=false}"

                //     //create issue
                //     def failIssue = [fields: [ // id or key must present for project.
                //               project: [id: "${projectID}"],
                //               summary: "Release Build ${env.BUILD_ID} has failed",
                //               description: "${issueDescription}",
                //               // id or name must present for issueType.
                //               issuetype: [id: '3']]]

                //     jiraNewIssue(
                //       issue: failIssue
                //     )

                //     //revert to unreleased
                //     def testVersion = [ id: '10205', // need to change this to get the correct id from the version name
                //         name: "${env.FIX_VERSIONS}",
                //         archived: true,
                //         released: true,
                //         description: 'desc',
                //         project: 'TEST' ]

                //     jiraEditVersion(
                //       id: '1000',
                //       version: testVersion
                //     )
                    
                //   }
                // }
            } //stage(deploy) closed bracket
            stage(promote) {
              when { expression { env.BUILDID > '0' && env.ORIG_REPO_PATH != env.REPO_PATH } }//skip build stage if build ID defined in Jira
              
              steps {
                publishChecks name: 'Promoting',
                                    text: 'Promoting -> manual status: in progress',
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
                                        "target": "${env.REPO_PATH}/${pipelineParams['repositoryName']}/${env.BUILDID}/"
                                        }
                                      ]
                            }"""
                )
                rtPublishBuildInfo (
                        serverId: pipelineParams['artifactoryGenericRegistry_ID']
                        //branch name
                        //docker image
                )

                //Delete the artifact from the origin
                script {
                  curlstr = "curl -k -X DELETE ${pipelineParams['artifactoryGenericRegistry_URL']}/artifactory/${env.ORIG_REPO_PATH}/${pipelineParams['repositoryName']}/${env.BUILDID}"

                  def deleteResponse = sh(
                        script: curlstr,
                        returnStdout: true
                  ).trim()

                  if (!deleteResponse)
                    {
                      println("Artifact ${env.BUILDID}/${pipelineParams['repositoryName']} successfuly deleted from ${env.ORIG_REPO_PATH}...")
                    }
                  else{
                    println("Something went wrong\n${deleteResponse}")
                  }

                }

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

                publishChecks name: 'Promoting',
                                    text: 'To view the artifactory please access it clicking the link below',
                                    status: 'COMPLETED',
                                    detailsURL: artifactoryLink
              }

              //post action to comment on issue and revert the state
              post{
                success {
                  //comment
                  jiraComment(
                      issueKey: "${env.ISSUE_KEY}",
                      body: "Build [${env.BUILD_DISPLAY_NAME}|${env.BUILD_URL}] succeded!"
                    )
                  //change deployment status
                  step([$class: 'IssueFieldUpdateStep', issueSelector: [$class: 'ExplicitIssueSelector', issueKeys: "${env.ISSUE_KEY}"], fieldId: '11100', fieldValue: 'Deployed' ]);
                }
                failure {
                  //change deployment status
                  step([$class: 'IssueFieldUpdateStep', issueSelector: [$class: 'ExplicitIssueSelector', issueKeys: "${env.ISSUE_KEY}"], fieldId: '11100', fieldValue: 'Deployment Failed' ]);
                  //transition status
                  step([$class: 'JiraIssueUpdateBuilder', jqlSearch: "issuekey = ${env.ISSUE_KEY}", workflowActionName: "${env.ORIG_STATUS}" ]);
                  //comment - after the transition to ensure there is no loop
                  jiraComment(
                      issueKey: "${env.ISSUE_KEY}",
                      body: "Build [${env.BUILD_DISPLAY_NAME}|${env.BUILD_URL}] has FAILED!"
                    )
                  //step([$class: 'IssueFieldUpdateStep', issueSelector: [$class: 'jql'], fieldId: 'status', fieldValue:  "Deployment Failed" ]);
                }
              }
            } //stage(promote) closed bracket
            // stage(jiracomment) {
            //   when { expression { issueKey } }
            //   steps {
            //     jiraComment(
            //       issueKey: issueKey,
            //       body: "Build [${env.BUILD_DISPLAY_NAME}|${env.BUILD_URL}] succeded!"
            //     )
            //   }
            // } //stage(jiracomment) closed bracket
          } //stages body closed bracket
        } //pipeline body closed bracket
} //def body closed bracket


