
package com.criticalsoftware.automation

class Pipelines{

    
def devops_call(String repoName) {
    println "The repository name inside the parameter is $repoName"

if (repoName == "sometext") {
    pipeline {
      agent any
      stages {
        stage('build') {
          steps {
            echo "condition one"
          }
        }
      }
    }
  } else {
    pipeline {
      agent any
      stages {
        stage('build') {
          steps {
            echo "condition two"
          }
        }
      }
    }
  }

}


}

