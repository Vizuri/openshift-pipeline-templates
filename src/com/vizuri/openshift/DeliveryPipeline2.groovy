#!/usr/bin/groovy
package com.vizuri.openshift


def call(body) {
	def utils = new com.vizuri.openshift.Utils();
	def pipelineParams= [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = pipelineParams
	body()

	pipeline {
		println ">>>> Starting DeliveryPipeline";

		println ">>>>>  Build Number ${BUILD_NUMBER}";
		println ">>>>>  JENKINS_URL ${JENKINS_URL}";
		println ">>>>>  BUILD_URL ${BUILD_URL}";
		println ">>>>>  JOB_URL ${JOB_URL}";
		

		def release_number;
		
		def feature = false;
		def develop = false;
		def release = false;
		

		echo ">>>>>>  Branch Name: " + BRANCH_NAME;

		if(BRANCH_NAME.startsWith("feature")) {
			slackSend color: "good", channel: 'cicd-develop', token: 'PsY21OKCkPM5ED01xurKwQkq', message: "Feature Job: ${env.JOB_NAME} with buildnumber ${env.BUILD_NUMBER} was started"
			feature = true;
		}
		else if(BRANCH_NAME.startsWith("develop")) {
			slackSend color: "good", channel: 'cicd-develop', token: 'PsY21OKCkPM5ED01xurKwQkq', message: "Develop Job: ${env.JOB_NAME} with buildnumber ${env.BUILD_NUMBER} was started"
			develop = true;
		}
		else if(BRANCH_NAME.startsWith("release")) {
			slackSend color: "good", channel: 'cicd-test', token: 'dMQ7l26s3pb4qa4AijxanODC', message: "Relase Job: ${env.JOB_NAME} with buildnumber ${env.BUILD_NUMBER} was started"
			release = true;
		}

		if(release) {
			def tokens = BRANCH_NAME.tokenize( '/' )
			branch_name = tokens[0]
			branch_release_number = tokens[1]

			release_number = branch_release_number

		}
		else {
			release_number = pipelineParams.snapshot_release_number
			ocp_project = pipelineParams.ocp_dev_project
		}

		if(feature || develop || release) {
			node('maven') {
				utils.buildJava(release_number)
				utils.testJava(release_number)
				stash name: 'artifacts'
			}
		}
		
		if(develop || release) {
			node ('maven') {
				unstash 'artifacts'
				utils.deployJava(release_number, "http://nexus-cicd.apps.52.91.247.224.xip.io")		
			}
		}
		
		
		if(develop) {
			node {
				deleteDir()
				unstash 'artifacts'
				img = utils.dockerBuild(pipelineParams.app_name, release_number)
				utils.dockerPush(img)
				//utils.scanImage(pipelineParams.app_name )
				utils.deployOpenshift(pipelineParams.ocp_dev_cluster, pipelineParams.ocp_dev_project, pipelineParams.app_name, release_number )
			}
		}
		if(release) {
			node {			
				deleteDir()
				unstash 'artifacts'
				img = utils.dockerBuild(pipelineParams.app_name, release_number)
				utils.dockerPush(img)
				stage('Confirm Deploy?') {
					milestone 1
					slackSend color: "good", channel: 'cicd-test', token: 'dMQ7l26s3pb4qa4AijxanODC', message: "Release ${release_numaber} of ${pipelineParams.app_name} is ready to move to test. Approve -> ${env.${BUILD_URL}}"
					input message: "Do you want to deploy ${pipelineParams.app_name} release ${release_number} to test?", submitter: "keudy"
				}
				utils.deployOpenshift(pipelineParams.ocp_test_cluster, pipelineParams.ocp_test_project, pipelineParams.app_name, release_number  )
			}
		}
		
	}


}
