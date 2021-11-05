//Question for Várzea. Shouldn't we have a repository to keep the docker images need for each repository, and just call them to build
//the image, send the image to registry, and then push the image to build the environment, instead of manually upload it to registry?

def call(body) {
    // evaluate the body block, and collect configuration into the objectdef
    pipelineParams= [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {

        environment{
          String DOCKER_IMAGE = pipelineParams['image']
          String DOCKER_REG_ARTIFACTORY = pipelineParams['registryUrl']
          String DOCKER_REG_ARTIFACTORY_TOKEN = pipelineParams['registryUrl']
        }

        agent any
        /* // Only available in steps block (???)
          parameters {
            string(name: 'DOCKER_IMAGE', defaultValue: pipelineParams['image'], description: 'path to image in docker registry')
            string(name: 'DOCKER_REG_ARTIFACTORY',  defaultValue: pipelineParams['registryUrl'], description: 'path to docker registry')
            password(name: 'DOCKER REG_ARTIFACTORY_TOKEN', defaultValue: pipelineParams['registryCredentialsId'], description: 'token to registry')
          }
          */
          stages {
            stage('build') {
              agent{
                docker {
                  //get image from the registry
                  /*
                  image "${params.DOCKER_IMAGE}"
                  registryUrl "${params.DOCKER_REG_ARTIFACTORY}"
                  registryCredentialsId "${params.DOCKER_REG_ARTIFACTORY_TOKEN}"
                  */
                  image DOCKER_IMAGE
                  registryUrl DOCKER_REG_ARTIFACTORY
                  registryCredentialsId DOCKER_REG_ARTIFACTORY_TOKEN

                  reuseNode true
                }
              }
              steps {
                //Link can't be literally here #########
                sh"""
                  echo Cloning Repo
                  git clone https://github.com/airfcms/hello_world.git
                 """
             }
            }
          }
          //Not the stage Várzea asked stage('unit testing')
          //For testing purposes
          stage('compile'){
                //Call cmake command to compile the CMakeLits.txt file inside the cloned repo
                //Repository name can't be literal ###########
                sh"""
                  mkdir hello_world/build
                  cd hello_world/build
                  cmake ..
                  make
                  ./hello_world
                """
          }
          //Not the stage Várzea asked stage(???)
          //For testing purposes
          //Push the artifact to Azure Artifactory Generic registry
          stage('deploy') {
            
          }
        }
}


