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

