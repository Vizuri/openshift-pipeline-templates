#!/usr/bin/groovy
package com.vizuri.openshift


def call(body) {
	def utils = new com.vizuri.openshift.Utils();
	def pipelineParams= [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = pipelineParams
	body()
	
	pipeline {
		environment {
			RELEASE_NUMBER = "";
		}
	
		try {
			println ">>>> Starting DeliveryPipeline";
			utils.init();
			echo "utils.isFeature():utils.isRelease():utils.isDevelop():${env.RELEASE_NUMBER}"
		
			if( utils.isFeature() || utils.isDevelop() || utils.isRelease()) {
				node('maven') {
					utils.buildJava()
					utils.testJava()
					utils.analyzeJava()
					stash name: 'artifacts'
				}
			}
			
			if(utils.isDevelop() || utils.isRelease()) {
				node ('maven') {
					unstash 'artifacts'
					utils.deployJava()
				}
			}
		} catch (e) {
			currentBuild.result = "FAILED"
			throw e
		} finally {
			utils.notifyBuild(currentBuild.result)
		}
	}
}
