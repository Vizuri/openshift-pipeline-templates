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
		node {
			checkout scm;
			
		}
	}
}
