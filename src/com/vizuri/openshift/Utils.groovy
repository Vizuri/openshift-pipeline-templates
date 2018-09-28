package com.vizuri.openshift


def helloWorld() {
   println("helloworkd");
}


def buildJava(release_number) {
      echo "In buildJava: ${release_number}"
      stage('Checkout') {
           echo "In checkout"
           checkout scm
      }
      stage('Build') {
           echo "In Build"
           sh "mvn -s configuration/settings.xml -DskipTests=true -Dbuild.number=${release_number} clean install"
      }

      stage ('test') {
             parallel (
                "unit tests": { sh 'mvn -s configuration/settings.xml -Dbuild.number=${release_number} test' },
                "integration tests": { sh 'mvn -s configuration/settings.xml -Dbuild.number=${release_number} integration-test' }
             )
             junit 'target/surefire-reports/*.xml'

             step([$class: 'XUnitBuilder',
                thresholds: [[$class: 'FailedThreshold', unstableThreshold: '1']],
                tools: [[$class: 'JUnitType', pattern: 'target/surefire-reports/*.xml']]])
      }
}


def dockerBuildOpenshift(ocp_cluster, ocp_project, app_name) {
         stage('DockerBuild') {
             echo "In DockerBuild: ${ocp_cluster} : ${ocp_project}"
             openshift.withCluster( "${ocp_cluster}" ) {
                  openshift.withProject( "${ocp_project}" ) {
                      def bc = openshift.selector("bc", "${app_name}")
                      echo "BC: " + bc
                      echo "BC Exists: " + bc.exists()
                      if(!bc.exists()) {
                         echo "BC Does Not Exist Creating"
                         bc = openshift.newBuild("--binary=true --strategy=docker --name=${pipelineParams.app_name}")
                      }
                      bc = bc.narrow("bc");
                      bc.startBuild("--from-dir .")
                      bc.logs('-f')
                  }
              }
          }
}

def deployOpenshift(ocp_cluster, ocp_project, app_name) {
          stage('Deploy') {
             openshift.withCluster( "${ocp_cluster}" ) {
                 openshift.withProject( "${ocp_project}" ) {
                      echo "In Deploy: ${openshift.project()} : ${ocp_project}"
                      def dc = openshift.selector("dc", "${app_name}")
                      echo "DC: " + dc
                      echo "DC Exists: " + dc.exists()
                      if(!dc.exists()) {
                            echo "DC Does Not Exist Creating"
                            dc = openshift.newApp("-f https://raw.githubusercontent.com/Vizuri/openshift-pipeline-templates/master/templates/springboot-dc.yaml -p IMAGE_NAME=172.30.1.1:5000/${ocp_project}/${app_name}:latest -p APP_NAME=${app_name}")
                      }
                      else {
                            dc = dc.narrow("dc")
                            dc.rollout("--latest")
                            //dc.logs('-f')
                      }
                }
             }
          }      
}

