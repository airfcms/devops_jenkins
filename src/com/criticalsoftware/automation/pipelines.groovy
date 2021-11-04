



def devops_call(String repoName) {
    println "The repository name inside the parameter is $repoName"
}


/*

currentBuild.result = "SUCCESS"
String buildAgentLabel = "" //add your build agent name here (???)
//noinspection GroovyUnusedAssignment
@Library('Pipeline-Global-Library')

try {
    stage("Build") {
        node(buildAgentLabel) {
            envSetup()  //(???)
            build()
        }
    }
/*
    stage("Unit Testing") {
        node(buildAgentLabel) {
            unitTesting()
        }
    }

    stage("SW Integration Testing") {
        node(buildAgentLabel) {
            functionalTesting()
        }
    }

    stage("HW/SW Integration Testing") {
        node(buildAgentLabel) {
            performanceTesting()
        }
    }

    stage("Promote") {
        node(buildAgentLabel) {
            promote()
        }
    }

    stage("Deploy") {
        node(buildAgentLabel) {
            deploy()
        }
    }

    stage("Static Analysis") {
        node(buildAgentLabel) {
            staticAnalysis()
        }
    }

*/
/*
}
catch (err) {
    echo err.toString()
    currentBuild.result = "FAILURE"
}

def envSetup(){
  // (??)  deleteDir() //delete jenkins working dir for this project
    checkout scm //clones the git repository
}

def build() {

    // Get the image from airfcms docker/repo registry in artifactory
    // Docker run the image to build the environment (virtualization)
    // git clone the repositories inside the image (??)
    // Compile the project inside the cloned repository(??)

}
/*
def staticAnalysis() {}

def unitTesting() {}

def deploy() {
    // Send the artifact to azure.artifactory generic repo (??)
}

def functionalTesting() {}

def performanceTesting() {}

def promote() {}
 */


