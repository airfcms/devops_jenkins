


def call(Map config) {

  node {
if (${config.name} == "sometext") {
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




