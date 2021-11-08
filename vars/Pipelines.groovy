//Cleaning is needed(Testing in needed -> Env.variables) and Integration with SonnarQube

def call(body) {
    // evaluate the body block, and collect configuration into the objectdef
    pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    scmUrl = scm.getUserRemoteConfigs()[0].getUrl()

    pipeline {
      environment{
        docker_image = pipelineParams['dockerImage']
      }
        agent any
          stages {
            stage('build') {
              agent{

                docker {
                  reuseNode true //Don't see the difference on::off ### From the consoleOutput it seems the image is removed when the building stage is finished. Need to check why!!! <---------------

                  image pipelineParams['dockerImage']
                  registryUrl pipelineParams['dockerRegistryUrl']
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
                  cmake -S ${pipelineParams['repositoryName']} -B ${pipelineParams['cmakeBuildDir']}
                  make -C ${pipelineParams['cmakeBuildDir']}
                  ./${pipelineParams['cmakeBuildDir']}/${pipelineParams['repositoryName']}
                 """
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
            stage('deploy') { //It seems that the docker image is remove before this stage !!IMPORTANT!!
              steps{
                rtUpload(
                      serverId: pipelineParams['artifactoryGenericRegistry_ID'],
                      spec: '''{
                                "files": [
                                           {
                                            "pattern": "*/${pipelineParams['repositoryName']}",
                                            "target": "build-repo/"
                                            }
                                         ]
                                }'''
                )
                rtPublishBuildInfo ( //Send notification and prevents an error.
                    serverId: pipelineParams['artifactoryGenericRegistry_ID']
                )
              }
            } //stage(deploy) closed bracket
            stage('static analysis') {
                environment {
                  scannerHome = tool 'SonarQubeScanner'
                }
                steps {
                  withSonarQubeEnv('sonarqube') {
                    sh "${env.scannerHome}/bin/sonar-scanner"
                  }
                  timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                  }
                }
            }
          } //stages body closed bracket
        } //pipeline body closed bracket
} //def body closed bracket


