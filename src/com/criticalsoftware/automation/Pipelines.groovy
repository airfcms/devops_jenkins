
package com.criticalsoftware.automation

class Pipelines{


def devops_call(String repoName) {
    
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

