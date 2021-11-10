//define the object for the buildGitHubCheckScript


//Cleaning is needed(Testing in needed -> Env.variables) and Integration with SonnarQube

def call(body) {
    // evaluate the body block, and collect configuration into the objectdef

    def check_runs = new com.criticalsoftware.automation.buildGithubCheckScript()

    pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    scmUrl = scm.getUserRemoteConfigs()[0].getUrl()
    //repoName = scmUrl.tokenize('/').last().split("\\.")[0] //https://stackoverflow.com/questions/45684941/how-to-get-repo-name-in-jenkins-pipeline

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
                script {
                  // https://medium.com/ni-tech-talk/custom-github-checks-with-jenkins-pipeline-ed1d1c94d99f
                  //get credential into privateKey
                  withCredentials([sshUserPrivateKey(credentialsId: 'github_ssh_jenkins', keyFileVariable: 'privateKey', passphraseVariable: '', usernameVariable: 'RicardoFARosa')]) {
                    try {
                        //Link can't be literally here #########
                        sh(script: "env | sort", returnStdout: true) //To check available global variables

                        //Work around because the declarative sintax bugs with deleteDir() and cleanWS()
                        sh(script: "rm -rf ${WORKSPACE}/*", returnStdout: true)

                        sh(script:"""
                          echo Cloning Repository in Docker Image Workspace
                          git clone ${scmUrl}
                          cd ${pipelineParams['repositoryName']}
                          git checkout ${env.BRANCH_NAME}
                          cd ..
                          cmake -S ${pipelineParams['repositoryName']} -B ${pipelineParams['cmakeBuildDir']}
                          make -C ${pipelineParams['cmakeBuildDir']}
                          ./${pipelineParams['cmakeBuildDir']}/${pipelineParams['repositoryName']}
                        """, returnStdout: true)

                        //send the result
                        check_runs.buildGithubCheck("${pipelineParams['repositoryName']}", '${GIT_COMMIT}', privateKey, 'success', "build")
                    } catch(Exception e) {
                        check_runs.buildGithubCheck("${pipelineParams['repositoryName']}", '${GIT_COMMIT}', privateKey, 'failure', "build")
                        echo "Exception: ${e}"
                      }
                  }
                }
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


