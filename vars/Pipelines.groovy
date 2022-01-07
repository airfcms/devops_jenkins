def call(body) {
    // evaluate the body block, and collect configuration into the objectdef
    pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()
    switch(pipelineParams['repositoryName'])
    {
	case "smoketest_project":
		GenericPipeline(pipelineParams)
	break;
	default:
		GenericPipeline(pipelineParams)
	break;
    }    
}
