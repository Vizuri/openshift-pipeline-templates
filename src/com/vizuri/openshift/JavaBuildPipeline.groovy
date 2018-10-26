#!/usr/bin/groovy
package com.vizuri.openshift


def call(body) {
	def utils = new com.vizuri.openshift.Utils();
	def pipelineParams= [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = pipelineParams
	body()

	pipeline {
		try {
			println ">>>> Starting DeliveryPipeline";

			def release_number;
			def feature = false;
			def develop = false;
			def release = false;

			echo ">>>>>>  Branch Name: " + BRANCH_NAME;

			if(BRANCH_NAME.startsWith("feature")) {
				utils.notifyBuild()
				feature = true;
			}
			else if(BRANCH_NAME.startsWith("develop")) {
				utils.notifyBuild()
				develop = true;
			}
			else if(BRANCH_NAME.startsWith("release")) {
				utils.notifyBuild()
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
					utils.analyzeJava()
					stash name: 'artifacts'
				}
			}

			if(develop || release) {
				node ('maven') {
					unstash 'artifacts'
					utils.deployJava(release_number, "http://nexus-cicd.apps.35.170.72.56.xip.io")
				}
			}
		} catch (e) {
			currentBuild.result = "FAILED"
			throw e
		} finally {
			// Success or failure, always send notifications
			utils.notifyBuild(currentBuild.result)
		}
	}
}
