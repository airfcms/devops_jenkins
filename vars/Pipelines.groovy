

def call(body) {
    // evaluate the body block, and collect configuration into the objectdef
    pipelineParams = [:]

    //Appears with null value.... why?!
    DOCKER_IMAGE = pipelineParams['dockerImage']
    DOCKER_REG_ARTIFACTORY = pipelineParams['dockerRegistryUrl']
    DOCKER_REG_ARTIFACTORY_TOKEN = pipelineParams['dockerRegistryUrl']
    scmUrl = scm.getUserRemoteConfigs()[0].getUrl()

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {
          environment{

            DOCKER_IMAGE = """
                            ${sh(returnStdout: true, script: "echo ${pipelineParams['dockerImage']}")}
                          """ //For some reason this is null but it echo's the right string
             /*
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
                  image 'csw-docker-registry/csw-airfcms-ubuntu'
                  registryUrl 'https://airfcms.jfrog.io/'
                  registryCredentialsId 'docker-registry'

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
            stage('deploy') { //it seems that the docker image is remove before this stage !!IMPORTANT!!
              steps{

                sh"""
                  ls -lha
                  pwd
                """
                rtServer (
                    id: 'artifactory_generic_repository',
                    url: 'http://40.67.228.51:8082/artifactory',
                    // If you're using Credentials ID:
                    credentialsId: 'artifact_registry'
                )


                rtUpload(
                      serverId: 'artifactory_generic_repository',
                      spec: '''{
                                "files": [
                                           {
                                            "pattern": "hello_world",
                                            "target": "artifactory/build-repo/"
                                            }
                                         ]
                                }''',
                      buildName: 'Hello_World'
                )

                rtPublishBuildInfo (
                    serverId: 'artifactory_generic_repository'
                )
              }
            }
          }


        }
}


