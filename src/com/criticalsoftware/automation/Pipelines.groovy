
package com.criticalsoftware.automation

//@Library('Pipeline-Global-Library') _




def call(Map config) {

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

return this

