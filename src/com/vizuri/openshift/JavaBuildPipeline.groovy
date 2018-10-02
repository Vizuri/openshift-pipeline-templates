package com.vizuri.openshift

def call(body) {
	def pipelineParams= [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = pipelineParams
	body()


	node('maven') {
		utils.buildJava(release_number)
		utils.testJava(release_number)
	}
}
