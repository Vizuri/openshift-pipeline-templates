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
}
def testJava(release_number) {
	echo "In testJava: ${release_number}"
	stage ('test') {
		parallel (
				"unit tests": { sh 'mvn -s configuration/settings.xml -Dbuild.number=${release_number} test' },
				"integration tests": { sh 'mvn -s configuration/settings.xml -Dbuild.number=${release_number} integration-test' }
				)
		junit 'target/surefire-reports/*.xml'

		step([$class: 'XUnitBuilder',
			thresholds: [
				[$class: 'FailedThreshold', unstableThreshold: '1']
			],
			tools: [
				[$class: 'JUnitType', pattern: 'target/surefire-reports/*.xml']
			]])
	}
}

def deployJava(release_number) {
	echo "In deployJava: ${release_number}"

	stage('Deploy Java') {
		echo "In Deploy"
		sh "mvn -s configuration/settings.xml -DskipTests=true -Dbuild.number=${release_number} deploy"
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
					bc = openshift.newBuild("--binary=true --strategy=docker --name=${app_name}").narrow("bc")
				}
				//bc = bc.narrow("bc");
				bc.startBuild("--from-dir .")

				bc.logs('-f')

				def builds = bc.related('builds')
				timeout(1) {
					builds.untilEach(1) {
						echo "In Look for bc status:" + it.object().status.phase
						//return (it.object().status.phase == "Complete")
						if(it.object().status.phase == "Failed") {
							return false
						}
						else if (it.object().status.phase == "Complete") {
							return true
						}
					}
				}



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
					dc = openshift.newApp("-f https://raw.githubusercontent.com/Vizuri/openshift-pipeline-templates/master/templates/springboot-dc.yaml -p IMAGE_NAME=docker-registry.default.svc:5000/${ocp_project}/${app_name}:latest -p APP_NAME=${app_name}")
				}
				//dc = dc.narrow("dc")
				def rm = dc.rollout()
				rm.latest()
				timeout(5) {
					dc.related('pods').untilEach(1) {
						//return (it.object().status.phase == "Running")
						if(it.object().status.phase == "Failed") {
							return false
						}
						else if (it.object().status.phase == "Running")	{
							return true
						}
					}
					//rm.status()
					//dc.logs('-f')
				}
			}
		}
	}
}
