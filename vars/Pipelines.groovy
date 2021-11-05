

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
    echo pipelineParams['dockerImage']
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
                  image /*'csw-docker-registry/csw-airfcms-ubuntu'*/ DOCKER_IMAGE
                  registryUrl /*'https://airfcms.jfrog.io/'*/ DOCKER_REGISTRY_URL
                  registryCredentialsId 'docker-registry' //DOCKER_REG_ARTIFACTORY_TOKEN

                  reuseNode true
                }
              }
              steps {
                //Link can't be literally here #########
                sh 'env' //To check available global variables
                sh 'rm -rf ${WORKSPACE}/*' //Work around because of deleteDir() and cleanWS() jenkins bugs

                sh"""
                  echo Cloning Repo
                  git clone ${scmUrl}
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


