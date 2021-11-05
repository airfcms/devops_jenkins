

def call(body) {
    // evaluate the body block, and collect configuration into the objectdef
    pipelineParams = [:]

    DOCKER_IMAGE = pipelineParams['dockerImage']
    DOCKER_REG_ARTIFACTORY = pipelineParams['dockerRegistryUrl']
    DOCKER_REG_ARTIFACTORY_TOKEN = pipelineParams['dockerRegistryUrl']
    scmUrl = scm.getUserRemoteConfigs()[0].getUrl()

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {

        environment{
          /*
          DOCKER_IMAGE = pipelineParams['dockerImage']
          DOCKER_REG_ARTIFACTORY = pipelineParams['dockerRegistryUrl']
          DOCKER_REG_ARTIFACTORY_TOKEN = pipelineParams['dockerRegistryUrl']
          scmUrl = scm.getUserRemoteConfigs()[0].getUrl()
          */
          DOCKER_CREDENTIALS_ID = credentials('JFrog_Artifactory_Docker_Registry')

        }

        agent any
          stages {
            stage('build') {
              agent{
                docker {
                  image 'csw-docker-registry/csw-airfcms-ubuntu' //DOCKER_IMAGE
                  registryUrl 'https://airfcms.jfrog.io/' //DOCKER_REG_ARTIFACTORY
                  registryCredentialsId 'docker-registry' //DOCKER_REG_ARTIFACTORY_TOKEN

                  reuseNode true
                }
              }
              steps {
                //Link can't be literally here #########

                echo 'Testing docker run from artifactory -- Success'
                echo sh (script: "ls -lha", returnStdout: true)
                sh 'printenv'
                sh 'echo ${scmUrl}'
                sh 'sudo rm -rf ${WORKSPACE}/'

                sh"""
                  echo Cloning Repo
                  git clone https://github.com/airfcms/hello_world.git
                  mkdir hello_world/build
                  cd hello_world/build
                  cmake ..
                  make
                  ./hello_world
                 """
             }
            }
            //Not the stage Várzea asked stage(???)
            //For testing purposes
            //Push the artifact to Azure Artifactory Generic registry
            /*stage('deploy') {
              steps{
               echo scmUrl
               echo scm.getUserRemoteConfigs()
              }
            }*/
          }


        }
}


