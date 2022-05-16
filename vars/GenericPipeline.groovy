import hudson.model.Run;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.checks.github.GitHubChecksPublisherFactory;

def call(Map pipelineParams) {

    scmUrl = scm.getUserRemoteConfigs()[0].getUrl()

    sonarDashboard = "/dashboard?id="
    sonarReportLink = ""
    String artifactoryLink = ""
    String cmpCheckStatus = ""

    INFERRED_BRANCH_NAME = env.BRANCH_NAME

    if (env.CHANGE_ID)
    {
        INFERRED_BRANCH_NAME = env.CHANGE_BRANCH
    }

    pipeline {
        agent any
          stages {          
            
            //
            // CMP Check
            //
            stage('cmp check'){
                when {
                    expression { pipelineParams['checkCmp'] == true }
                }
                options {
                    timeout(time: 5, unit: "MINUTES")
                }
                steps {
                    publishChecks name: 'CMP Check'
                    
                    script {
                        // Resolve dependencies
                        def user_id = sh(returnStdout: true, script: 'id -u').trim()
                        
                        if (user_id == "0") {
                            println("Installing dependencies")
                            sh '''
                                apt update
                                apt install -y python3 python3-pip
                                pip3 install gitpython xmlschema requests lxml colorama
                            '''
                        } else {
                            println("Executing as non-root user ($user_id), skipping dependencies install.")
                        }
                    }
                    
                    // Update submodules (workaround)
                    withCredentials([sshUserPrivateKey(credentialsId: 'github_airfcmssvc', keyFileVariable: 'ssh_key')]){
                        sh '''
                            GIT_SSH_COMMAND="ssh -i $ssh_key -o IdentitiesOnly=yes -o StrictHostKeyChecking=no" git submodule init
                            GIT_SSH_COMMAND="ssh -i $ssh_key -o IdentitiesOnly=yes -o StrictHostKeyChecking=no" git submodule update
                        '''
                    }
                    
                    // Check CMP                    
                    script {
                        def cmds = ["python3 cmpchecker/cmpcheck.py --cmp-xsd cmpchecker/cmp_schema.xsd --cmp-xml cmp_val_1/cmp_config.xml cmp_val_2/cmp_config.xml cmp_val_3/cmp_config.xml"]
   
                        try {
                            cmds.each {
                                def code = sh(script: it, returnStatus: true)
                                
                                if (code != 0) {
                                    println("CMP Check exit with code $code")
                                    unstable(message: "CMP Check exited with code $code != 0")

                                    if (code == 1){
                                        cmpCheckStatus = 'UNSTABLE'
                                    }
                                    else if (code == 2){
                                        cmpCheckStatus = 'FAILURE'
                                    }
                                }                                
                            }
                        }
                        catch (err) {                                        
                            unstable(message: "CMP Check exited with code != 0")
                        }
                    }
                }
            }
          
            //
            // Build
            //
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
                              status: 'IN_PROGRESS'

                sh 'env | sort' //To check available global variables

                //Work around because the declarative sintax bugs with deleteDir() and cleanWS()
                sh 'rm -rf ${WORKSPACE}/*'

                withCredentials([sshUserPrivateKey(credentialsId: 'github_airfcmssvc', keyFileVariable: 'ssh_key')]){
                    sh"""
                      echo Cloning Repository in Docker Image Workspace
                      
                      GIT_SSH_COMMAND="ssh -i $ssh_key -o IdentitiesOnly=yes -o StrictHostKeyChecking=no" git clone -b ${INFERRED_BRANCH_NAME} ${scmUrl}
                                            
                      cmake -S ${pipelineParams['repositoryName']} -B ${pipelineParams['cmakeBuildDir']}
                      make -C ${pipelineParams['cmakeBuildDir']}
                     """
                 }
                //sh 'sleep 60' //For testing but couldn't see the changes...
				        publishChecks name: 'Build',
                              status: 'COMPLETED'
             }
            } //stage(build) closed bracket
            
            //
            // Unit Testnig
            //
            stage('unit testing'){
                steps {
                    publishChecks name: 'Unit Testing'
                }
            }
            
            //
            // Integration Testing
            //
            stage('sw integration testing') {
                steps {
                    publishChecks name: 'Integration Testing'
                }
            }
            
            //
            // HW/SW Integration Testing
            //
            stage('hw/sw integration testing') {
                steps {
                  publishChecks name: 'HW/SW Integration Testing'
                }
            }
            
            //
            // Static Analysis
            //
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
                    sh 'env' //to see if i have the SonarHost link to use instead of writing in a variable - env.SONAR_xx check jenkinsLog
                    sh "cd ${WORKSPACE}/${pipelineParams['repositoryName']}; ${scannerHome}/bin/sonar-scanner -X -Dproject.settings=sonar-project.properties"
                    script {
                      sonarReportLink = env.SONAR_HOST_URL + sonarDashboard + pipelineParams['repositoryName']
                    }
                  }

                  timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                  }

                  //sh 'sleep 60' //For testing but couldn't see the changes...
				          publishChecks name: 'Static Analysis',
                                text: 'To view the SonarQube report please access it clicking the link below',
                                status: 'COMPLETED',
                                detailsURL: sonarReportLink
                }
            } //stage(static analysis) closed bracket
            
            //
            // Deploy
            //
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
                    //check were Jenkins are in the file system to see the path value in path at publishChecks call
                    //sh 'ls -la'
			  	          publishChecks name: 'Deployment',
                                  text: 'To view the artifactory please access it clicking the link below',
                                  status: 'COMPLETED',
                                  detailsURL: artifactoryLink//,
                                  //annotations: [[path : "hello_world/src/main.*", startLine: 1, endLine: 5, message: 'testing annotations in message', title: 'testing annotations in title' ]]
                }
            } //stage(deploy) closed bracket
            
            //
            // Promote
            //
            stage(promote){
              steps{
                rtServer (
                        id: pipelineParams['artifactoryGenericRegistry_ID'],
                        url: "${pipelineParams['artifactoryGenericRegistry_URL']}/artifactory",
                        credentialsId: 'artifact_registry'
                    )
                // rtPromote (
                //   buildName: pipelineParams['repositoryName'] + ' :: ' + INFERRED_BRANCH_NAME,
                //   buildNumber: env.BUILD_ID,
                //   serverId: pipelineParams['artifactoryGenericRegistry_ID'],
                //   // Name of target repository in Artifactory
                //   targetRepo: 'staging-repo',
                //   // Comment and Status to be displayed in the Build History tab in Artifactory
                //   comment: 'Promoting ' + env.BUILD_ID + ' to Staging',
                //   status: 'Released',
                //   // Specifies the source repository for build artifacts.
                //   sourceRepo: 'build-repo',
                //   // Indicates whether to copy the files. Move is the default.
                //   copy: true
                // )
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
            
            //
            // CMP Validation
            //
            stage('cmp validation'){
                when {
                    expression { checkCmp == true }
                }
                
                // The build will be marked as FAILURE or UNSTABLE due to CMP check errors only at the end to avoid an
                // early pipeline termination.
                steps {
                    publishChecks name: 'CMP Validation'

                    script {
                        echo "Build status: " + currentBuild.result
                        if (currentBuild.result != "FAILURE") {
                            if (cmpCheckStatus == 'UNSTABLE'){
                                echo "CMP Check finished with errors, marking build as UNSTABLE"
                                currentBuild.result = 'UNSTABLE'
                            }
                            else if (cmpCheckStatus == 'FAILURE'){
                                echo "CMP Check finished with errors, marking build as FAILURE"
                                currentBuild.result = 'FAILURE'
                            }
                        }
                    }
                }
            }
            
          } //stages body closed bracket
        } //pipeline body closed bracket
} //def body closed bracket


