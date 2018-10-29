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
		
		def projectFolder;
		if(pipelineParams.project_folder) {
			echo "setting project_folder: ${pipelineParams.project_folder}"
			projectFolder = pipelineParams.project_folder
		}
		else {
			echo "setting project_folder: default"
			projectFolder = "./"
		}
	
		try {
			println ">>>> Starting DeliveryPipeline";			
			utils.init();	
			echo "utils.isFeature():utils.isRelease():utils.isDevelop():${env.RELEASE_NUMBER}"
		
			if( utils.isFeature() || utils.isDevelop() || utils.isRelease()) {
				node('maven') {
					utils.buildJava(projectFolder)
					utils.testJava(projectFolder)
					utils.analyzeJava(projectFolder)
					stash name: 'artifacts'
				}
			}
			
			if(utils.isRelease() ||  utils.isDevelop(  ) {
				node ('maven') {
					unstash 'artifacts'
					utils.deployJava(projectFolder)
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
					utils.scanImage(pipelineParams.app_name )	
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
			utils.notifyBuild(currentBuild.result)
		}
	}
}
