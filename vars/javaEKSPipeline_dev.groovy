//this is function define a function name call()
def call(Map configMap){
    pipeline {
        agent {
          // Jenkins agent (worker node) where this pipeline runs
          label 'AGENT-1'
        }
        options{
            timeout(time: 30, unit: 'MINUTES') // Fail the build if it runs longer than 30 minutes
            disableConcurrentBuilds() // Prevent multiple builds from running at the same time (or)Prevent parallel runs of same job
            //retry(1)
        }
        // Parameters shown when triggering the CI job manually
        parameters{
            // Only one control here:
            // deploy = false → only build & push image
            // deploy = true  → build, push, AND trigger CD pipeline
            booleanParam(name: 'deploy', defaultValue: false, description: 'Select to deploy or not')
        }
        environment {
            // appVersion = '' // this will become global, we can use across pipeline
            // account_id = ''
            region = 'us-east-1'
            project = configMap.get("project")
            component = configMap.get("component")
            // CI always builds DEV image
            // Higher environments (qa/prod) are handled by CD
            targetEnv  = 'dev'
        }
        stages{
            stage('Read The Version') {
                steps {
                    script {
                        // Resolve the correct AWS account ID for this environment
                        env.account_id = pipelineGlobals.getAccountID(env.targetEnv)
                        echo "Using AWS Account ID: ${env.account_id} for environment: ${env.targetEnv}"

                        // Read the version from pom.xml
                        // Store it in a Jenkins environment variable (env.appVersion) so it is available globally
                        // across all stages (Docker build, Deploy, etc.), not just inside this script block
                        // def pom = readMavenPom file: "${component}/pom.xml" //if its monorepo with multiple service
                        def pom = readMavenPom file: "pom.xml"
                        env.appVersion = pom.version
                        echo "App version: ${env.appVersion}"

                    }
                }
            }

            stage('Build'){
                steps { 
                    // dir("${component}") { //if its monorepo with multiple service
                    //     //mvn clean package does THREE things: Downloads dependencies, Compiles source code, Packages into a JAR file
                    //     sh 'mvn clean package' 
                    // }
                    sh 'mvn clean package'
                }
            }

            stage('Docker Build'){
                steps {
                    withAWS(region: 'us-east-1', credentials: "aws-creds-${env.targetEnv}"){
                        // dir("${component}") {} //if its monorepo with multiple service
                        script {
                            // Docker image tag (build-once strategy)
                            def repo = "${env.account_id}.dkr.ecr.${region}.amazonaws.com/${project}/${env.targetEnv}/${component}:${env.appVersion}"
                            sh """
                                echo "Logging into ECR..."
                                aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${env.account_id}.dkr.ecr.${region}.amazonaws.com

                                echo "Building Docker image: ${repo}"
                                docker build -t ${repo} .

                                docker images

                                echo "Pushing image to ECR..."
                                docker push ${repo}

                                echo "Docker image successfully pushed to ${repo}"
                            """
                        }
                    }
                }
            }

            stage('Deploy'){
                when {
                    // CD is triggered only when deploy=true
                    expression { params.deploy }
                }
                steps {
                    // CI automatically deploys ONLY to DEV
                    // QA / PROD deployments are triggered manually from CD
                    /*
                    CI → CD CONNECTION HAPPENS HERE
                    We pass TWO VALUES to CD:
                    1. version     : Docker image tag
                    2. ENVIRONMENT : target environment
                    */
                    build job: "../${component}-cd", parameters: [
                    string(name: 'version', value: "${env.appVersion}"),
                    string(name: 'ENVIRONMENT', value: "${env.targetEnv}"),
                    ], wait: true   
                }
            }
            
        }
        post {
            always {
                echo "This section runs always."
                deleteDir()
            }

            success {
                echo "Pipeline completed successfully."
            }

            failure {
                echo "Pipeline failed."
            }
        }   
    }
}