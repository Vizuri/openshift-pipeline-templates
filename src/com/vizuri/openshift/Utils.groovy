package com.vizuri.openshift

//def containerRegistry = "docker-registry.default.svc:5000"
class Globals {
	static String imageBase = "ae86b1744d79011e8923c025188aea9c-1829846909.us-east-1.elb.amazonaws.com"
	static String imageNamespace = "vizuri"
	static String containerRegistry = "https://ae86b1744d79011e8923c025188aea9c-1829846909.us-east-1.elb.amazonaws.com"
	//def containerRegistry = "docker-registry.default.svc:5000"

}


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
	stage ('Unit Test') {
		parallel (
				"unit tests": { sh "mvn -s configuration/settings.xml -Dbuild.number=${release_number} test" },
				"integration tests": { sh "mvn -s configuration/settings.xml -Dbuild.number=${release_number} integration-test" }
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

def analyzeJava(projectFolder = "./") {
	stage('SonarQube Analysis') {
		//unstash "project-stash"

		
		sh "ls ${projectFolder}"		
		sh "cat ${projectFolder}/pom.xml"
		
		def pom = readMavenPom file: "${projectFolder}/pom.xml"
		
		writeFile encoding: 'UTF-8', file: 'sonar-project.properties', text: """
		  sonar.projectBaseDir=${projectFolder}
          sonar.projectKey=$pom.groupId:$pom.artifactId
          sonar.projectName=$pom.name
          sonar.projectVersion=$pom.version
	      sonar.java.binaries=target/classes
	      sonar.tests=target/jacoco.exec
          sonar.sources=src/main/java"""
		archive 'sonar-project.properties'
		
		sh "cat sonar-project.properties"

		def scannerHome = tool 'sonar';

		withSonarQubeEnv('sonar') { sh "${scannerHome}/bin/sonar-scanner" }

	}


//	stage("Quality Gate"){
//		timeout(time: 1, unit: 'HOURS') {
//			def qg = waitForQualityGate()
//			if (qg.status != 'OK') {
//				error "Pipeline aborted due to quality gate failure: ${qg.status}"
//			}
//		}
//	}
}

def deployJava(release_number, nexus_url) {
	echo "In deployJava: ${release_number}"

	stage('Deploy Build Artifact') {
		echo "In Deploy"
		if(nexus_url != null) {
			sh "mvn -s configuration/settings.xml -DskipTests=true -Dbuild.number=${release_number} -Dnexus.url=${nexus_url} deploy"
		}
		else {
			sh "mvn -s configuration/settings.xml -DskipTests=true -Dbuild.number=${release_number} deploy"
		}
	}
}
def dockerBuild(app_name, tag) {
	stage('Container Build') {
		echo "In DockerBuild: ${app_name} "
		docker.withRegistry(Globals.containerRegistry, "docker-credentials") {
			def img = docker.build("${Globals.imageNamespace}/${app_name}:${tag}")
			return img
		}
	}
}
def scanImage(app_name, tag) {
	stage('Container Scan') {
		writeFile file: 'anchore_images', text: "${Globals.imageBase}/${Globals.imageNamespace}/${app_name}:${tag} Dockerfile"
		sh 'cat anchore_images'
		anchore name: 'anchore_images'
	}
}

def dockerPush(img) {
	stage('Container Push') {
		docker.withRegistry(Globals.containerRegistry, "docker-credentials") {
			echo "In DockerPush:"
			img.push()
		}
	}
}

def dockerBuildOpenshift(ocp_cluster, ocp_project, app_name) {
	stage('Container Build') {
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
def confirmDeploy(app_name, release_number, ocp_project) {
	stage("Confirm Deploy to ${ocp_project}?") {
		notify(ocp_project, "Release ${release_number} of ${app_name} is ready for test test. Promote release here ${JOB_URL}")
		input message: "Do you want to deploy ${app_name} release ${release_number} to ${env}?", submitter: "keudy"
	}
}


def deployOpenshift(ocp_cluster, ocp_project, app_name, tag) {
	stage("Deploy Openshift ${ocp_project}") {
		echo "In Deploy: ${ocp_cluster} : ${ocp_project} : ${app_name}"
		openshift.withCluster( "${ocp_cluster}" ) {
			openshift.withProject( "${ocp_project}" ) {
				def dc = openshift.selector("dc", "${app_name}")
				echo "DC: " + dc
				echo "DC Exists: " + dc.exists()
				if(!dc.exists()) {
					echo "DC Does Not Exist Creating"
					//dc = openshift.newApp("-f https://raw.githubusercontent.com/Vizuri/openshift-pipeline-templates/master/templates/springboot-dc.yaml -p IMAGE_NAME=${Globals.imageBase}/${ocp_project}/${app_name}:latest -p APP_NAME=${app_name}").narrow("dc")
					dc = openshift.newApp("-f https://raw.githubusercontent.com/Vizuri/openshift-pipeline-templates/master/templates/springboot-dc.yaml -p IMAGE_NAME=${Globals.imageBase}/${Globals.imageNamespace}/${app_name}:${tag} -p APP_NAME=${app_name}").narrow("dc")
				}
				else {
					def dcObject = dc.object()
					dcObject.spec.template.spec.containers[0].image = "${Globals.imageBase}/${Globals.imageNamespace}/${app_name}:${tag}"
					openshift.apply(dcObject)
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

def getSlackToken(channel) {
	def token;
	if(channel.equals("cicd-feature")) {
		token = "PsY21OKCkPM5ED01xurKwQkq";
	}
	else if (channel.equals("cicd-develop")) {
		token = "PsY21OKCkPM5ED01xurKwQkq";
	}
	else if (channel.equals("cicd-test")) {
		token = "dMQ7l26s3pb4qa4AijxanODC";
	}
	else if (channel.equals("cicd-prod")) {
		token = "HW5G7kmVdRU6XyDJrcKvdyQA";
	}
	return token;
}

def notify(ocp_project, message) {
	def channel;
	if(ocp_project.sontains("test")) {
		channel = "cicd-test"
	}
	else if(ocp_project_contains("prod")) {
		channel = "cicd-prod"
	}

	def token = getSlackToken(channel);
	slackSend color: "good", channel: channel, token: token, message: message
}

def notifyBuild(String buildStatus = 'STARTED') {
	// build status of null means successful
	buildStatus =  buildStatus ?: 'SUCCESSFUL'

	echo "In notifyBuild ${buildStatus} : ${BRANCH_NAME}"

	def buildType;
	def channel;
	def token = getSlackToken(channel);

	if(BRANCH_NAME.startsWith("feature")) {
		buildType = "Feature"
		channel = "cicd-develop"
	}
	else if(BRANCH_NAME.startsWith("develop")) {
		buildType = "Develop"
		channel = "cicd-develop"
	}
	else if(BRANCH_NAME.startsWith("release")) {
		buildType = "Release"
		channel = "cicd-test"
	}

	if (buildStatus == 'STARTED') {
		slackSend color: "good", channel: channel, token: token, message: "${buildType} Job: ${BRANCH_NAME} with buildnumber ${env.BUILD_NUMBER} was started"
	} else if (buildStatus == 'SUCCESS') {
		slackSend color: "good", channel: channel, token: token, message: "${buildType} Job: ${BRANCH_NAME} with buildnumber ${env.BUILD_NUMBER} completed successfully"
	} else {
		slackSend color: "danger", channel: channel, token: token, message: "${buildType} Job: ${BRANCH_NAME} with buildnumber ${env.BUILD_NUMBER} failed"
	}

}

