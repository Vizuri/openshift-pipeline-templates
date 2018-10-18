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

def deployJava(release_number, nexus_url) {
	echo "In deployJava: ${release_number}"

	stage('Deploy Java') {
		echo "In Deploy"
		if(nexus_url != null) {
			sh "mvn -s configuration/settings.xml -DskipTests=true -Dbuild.number=${release_number} -Dnexus.url=${nexus_url} deploy"
		}
		else {
			sh "mvn -s configuration/settings.xml -DskipTests=true -Dbuild.number=${release_number} deploy"
		}
	}
}
def dockerBuild(app_name) {

	stage('DockerBuild') {
		echo "In DockerBuild: ${app_name} "
		def img = docker.build("52.91.247.224:30080/vizuri/${app_name}:latest")
		return img
	}
}

def dockerPush(img) {
	stage('DockerPush') {
		docker.withRegistry('http://52.91.247.224:30080', 'docker-credentials') {			
			echo "In DockerBuild: ${app_name} "
			docker.push(img)
		}
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
				def builds = bc.startBuild("--from-dir .")

				builds.logs('-f')

				echo("BUILD Finished")

				timeout(5) {
					builds.untilEach(1) {
						echo "In Look for bc status:" + it.count() + ":" + it.object().status.phase
						if(it.object().status.phase == "Failed") {
							currentBuild.result = 'FAILURE'
							error("Docker Openshift Build Failed")
						}
						return (it.object().status.phase == "Complete")
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
				def rm = dc.rollout()
				rm.latest()

				timeout(5) {
					def latestDeploymentVersion = openshift.selector('dc',"${app_name}").object().status.latestVersion
					echo "Got LatestDeploymentVersion:" + latestDeploymentVersion
					def rc = openshift.selector('rc', "${app_name}-${latestDeploymentVersion}")
					echo "Got RC" + rc
					rc.untilEach(1){
						def rcMap = it.object()
						return (rcMap.status.replicas.equals(rcMap.status.readyReplicas))
					}
				}
			}
		}
	}
}
