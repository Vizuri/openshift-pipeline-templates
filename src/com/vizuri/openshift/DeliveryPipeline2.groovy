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

		def image_tag="v1.${BUILD_NUMBER}";

		def release_number;
		
		def feature = false;
		def develop = false;
		def release = false;
		

		echo ">>>>>>  Branch Name: " + BRANCH_NAME;

		if(BRANCH_NAME.startsWith("feature")) {
			feature = true;
		}
		else if(BRANCH_NAME.startsWith("develop")) {
			develop = true;
		}
		else if(BRANCH_NAME.startsWith("release")) {
			release = true;
		}

		if(BRANCH_NAME.startsWith("release")) {
			def tokens = BRANCH_NAME.tokenize( '/' )
			branch_name = tokens[0]
			branch_release_number = tokens[1]

			release_number = branch_release_number

		}
		else {
			release_number = pipelineParams.snapshot_release_number
			ocp_project = pipelineParams.ocp_dev_project
		}

		if(feature || develop) {
			node('maven') {
				utils.buildJava(release_number)
				utils.testJava(release_number)
				stash name: 'artifacts'
			}
		}
		
		if(develop || release) {
			unstash 'artifacts'
			utils.deployJava(release_number, "http://nexus-cicd.apps.52.91.247.224.xip.io")		
		}
		
		
		if(develop) {
			node {
				unstash 'artifacts'
				img = utils.dockerBuild(pipelineParams.app_name)
				utils.dockerPush(img)
				//utils.scanImage(pipelineParams.app_name )
				utils.deployOpenshift(pipelineParams.ocp_dev_cluster, pipelineParams.ocp_dev_project, pipelineParams.app_name )
			}
		}
		if(release) {
			node {			
				stage('Confirm Deploy?') {
					milestone 1
					input message: "Do you want to deploy ${pipelineParams.app_name} release ${release_number} to test?", submitter: "keudy"
				}
				utils.deployOpenshift(pipelineParams.ocp_test_cluster, pipelineParams.ocp_test_project, pipelineParams.app_name )
			}
		}
		
	}
}
