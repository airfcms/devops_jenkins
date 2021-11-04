

def call(body) {
    // evaluate the body block, and collect configuration into the objectdef
    pipelineParams= [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {
        // our complete declarative pipeline can go in here
        agent any
          stages {
            stage('build') {
              steps {
                echo "condition one"
                println pipelineParams['name']
             }
            }
          }
        }
}


