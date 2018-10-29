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
			
			if(utils.isFeature() || utils.isRelease()) {
				node ('maven') {
					unstash 'artifacts'
					utils.deployJava()
				}
			}


			if(utils.isDevelop()) {
				node {
					deleteDir()
					unstash 'artifacts'
					img = utils.dockerBuild(pipelineParams.app_name)					
					utils.dockerPush(img)
					utils.deployOpenshift(pipelineParams.ocp_dev_cluster, pipelineParams.ocp_dev_project, pipelineParams.app_name )
				}
			}
			if(utils.isRelease()) {
				node {
					deleteDir()
					unstash 'artifacts'
					img = utils.dockerBuild(pipelineParams.app_name)
					utils.dockerPush(img)
					//utils.scanImage(pipelineParams.app_name, env.RELEASE_NUMBER )	
					utils.confirmDeploy(pipelineParams.app_name,pipelineParams.ocp_test_project)			
					utils.deployOpenshift(pipelineParams.ocp_test_cluster, pipelineParams.ocp_test_project, pipelineParams.app_name  )
					utils.confirmDeploy(pipelineParams.app_name,pipelineParams.ocp_prod_project)			
					utils.deployOpenshift(pipelineParams.ocp_prod_cluster, pipelineParams.ocp_prod_project, pipelineParams.app_name  )
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
