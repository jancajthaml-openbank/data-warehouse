def DOCKER_IMAGE

def dockerOptions() {
    String options = "--pull "
    options += "--label 'org.opencontainers.image.source=${env.GIT_URL}#${env.CHANGE_BRANCH}' "
    options += "--label 'org.opencontainers.image.created=${env.RFC3339_DATETIME}' "
    options += "--label 'org.opencontainers.image.revision=${env.GIT_COMMIT}' "
    options += "--label 'org.opencontainers.image.licenses=${env.LICENSE}' "
    options += "--label 'org.opencontainers.image.authors=${env.PROJECT_AUTHOR}' "
    options += "--label 'org.opencontainers.image.title=${env.PROJECT_NAME}' "
    options += "--label 'org.opencontainers.image.description=${env.PROJECT_DESCRIPTION}' "
    options += "."
    return options
}

def getVersion() {
    String[] versions = (sh(
        script: 'git fetch --tags --force 2> /dev/null; tags=\$(git tag --sort=-v:refname | head -1) && ([ -z \${tags} ] && echo v0.0.0 || echo \${tags})',
        returnStdout: true
    ).trim() - 'v').split('\\.')
    String major = versions[0]
    String minor = versions[1]
    Integer patch = Integer.parseInt(versions[2], 10)
    String version = "${major}.${minor}.${patch + 1}"
    return version
}

def artifactory = Artifactory.server "artifactory"

pipeline {

    agent {
        label 'docker'
    }

    options {
        skipDefaultCheckout(true)
        ansiColor('xterm')
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        disableConcurrentBuilds()
        disableResume()
        timeout(time: 10, unit: 'MINUTES')
        timestamps()
    }

    stages {

        stage('Checkout') {
            steps {
                script {
                    currentBuild.displayName = "#${currentBuild.number} - ${env.CHANGE_BRANCH} (?)"
                }
                deleteDir()
                checkout(scm)
            }
        }

        stage('Setup') {
            steps {
                script {
                    env.RFC3339_DATETIME = sh(
                        script: 'date --rfc-3339=ns',
                        returnStdout: true
                    ).trim()
                    env.GIT_COMMIT = sh(
                        script: 'git log -1 --format="%H"',
                        returnStdout: true
                    ).trim()
                    env.GIT_URL = sh(
                        script: 'git ls-remote --get-url',
                        returnStdout: true
                    ).trim()
                    env.ARCH = sh(
                        script: 'dpkg --print-architecture',
                        returnStdout: true
                    ).trim()

                    env.VERSION = getVersion()
                    env.LICENSE = "Apache-2.0"
                    env.PROJECT_NAME = "openbank data-warehouse"
                    env.PROJECT_DESCRIPTION = "OpenBanking Data Warehouse service"
                    env.PROJECT_AUTHOR = "${env.CHANGE_AUTHOR_DISPLAY_NAME} <${env.CHANGE_AUTHOR_EMAIL}>"

                    currentBuild.displayName = "#${currentBuild.number} - ${env.CHANGE_BRANCH} (${env.VERSION})"
                }
            }
        }

        stage('Fetch Dependencies') {
            agent {
                docker {
                    image 'jancajthaml/scala:amd64'
                    args "--entrypoint='' -u 0"
                    reuseNode true
                }
            }
            steps {
                script {
                    sh """
                        ${env.WORKSPACE}/dev/lifecycle/sync \
                        --source ${env.WORKSPACE}/services/data-warehouse
                    """
                }
            }
        }

        stage('Static Analysis') {
            agent {
                docker {
                    image 'jancajthaml/scala:amd64'
                    args "--entrypoint='' -u 0"
                    reuseNode true
                }
            }
            steps {
                script {
                    sh """
                        ${env.WORKSPACE}/dev/lifecycle/lint \
                        --source ${env.WORKSPACE}/services/data-warehouse
                    """
                    sh """
                        ${env.WORKSPACE}/dev/lifecycle/sec \
                        --source ${env.WORKSPACE}/services/data-warehouse
                    """
                }
            }
        }

        stage('Unit Test') {
            agent {
                docker {
                    image 'jancajthaml/scala:amd64'
                    args "--entrypoint='' -u 0"
                    reuseNode true
                }
            }
            steps {
                script {
                    sh """
                        ${env.WORKSPACE}/dev/lifecycle/test \
                        --source ${env.WORKSPACE}/services/data-warehouse \
                        --output ${env.WORKSPACE}/reports/unit-tests
                    """
                }
            }
        }

        stage('Compile') {
            agent {
                docker {
                    image 'jancajthaml/scala:amd64'
                    args "--entrypoint='' -u 0"
                    reuseNode true
                }
            }
            steps {
                script {
                    sh """
                        ${env.WORKSPACE}/dev/lifecycle/package \
                        --arch linux/${env.ARCH} \
                        --source ${env.WORKSPACE}/services/data-warehouse \
                        --output ${env.WORKSPACE}/packaging/bin
                    """
                }
            }
        }

        stage('Package Debian') {
            agent {
                docker {
                    image 'jancajthaml/debian-packager:latest'
                    args "--entrypoint='' -u 0"
                    reuseNode true
                }
            }
            steps {
                script {
                    sh """
                        ${env.WORKSPACE}/dev/lifecycle/debian \
                        --version ${env.VERSION} \
                        --arch ${env.ARCH} \
                        --pkg data-warehouse \
                        --source ${env.WORKSPACE}/packaging
                    """
                }
            }
        }

        stage('BlackBox Test') {
            agent {
                docker {
                    image "jancajthaml/bbtest:${env.ARCH}"
                    args """-u 0"""
                    reuseNode true
                }
            }
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                script {
                    echo "A"
                    cid = sh(
                        script: 'hostname',
                        returnStdout: true
                    ).trim()

                    echo "B"

                    docker.image("${env.ARTIFACTORY_DOCKER_REGISTRY}/docker-local/openbank/postgres:0.0.1").withRun("") { db ->

                        echo "C"

                        docker.image("jancajthaml/bbtest:${env.ARCH}").withRun(options) { c ->

                            echo "D"

                            options = """
                                |-e IMAGE_VERSION=${env.VERSION}
                                |-e POSTGRES_HOSTNAME=${db.id}
                                |-e UNIT_VERSION=${env.VERSION}
                                |-e UNIT_ARCH=${env.ARCH}
                                |--volumes-from=${cid}
                                |-v /var/run/docker.sock:/var/run/docker.sock:rw
                                |-v /var/lib/docker/containers:/var/lib/docker/containers:rw
                                |-v /sys/fs/cgroup:/sys/fs/cgroup:ro
                                |-u 0
                            """.stripMargin().stripIndent().replaceAll("[\\t\\n\\r]+"," ").stripMargin().stripIndent()

                            sh "docker exec -t ${c.id} python3 ${env.WORKSPACE}/bbtest/main.py"
                        }
                    }

                }
            }
        }

        stage('Package Docker') {
            steps {
                script {
                    DOCKER_IMAGE = docker.build("${env.ARTIFACTORY_DOCKER_REGISTRY}/docker-local/openbank/data-warehouse:${env.VERSION}", dockerOptions())
                }
            }
        }

        stage('Publish') {
            steps {
                script {
                    docker.withRegistry("http://${env.ARTIFACTORY_DOCKER_REGISTRY}", 'jenkins-artifactory') {
                        DOCKER_IMAGE.push()
                    }
                    artifactory.upload spec: """
                    {
                        "files": [
                            {
                                "pattern": "${env.WORKSPACE}/packaging/bin/fio-bco-rest-linux-(*)",
                                "target": "generic-local/openbank/fio-bco/linux/{1}/${env.VERSION}/fio-bco-rest",
                                "recursive": "false"
                            },
                            {
                                "pattern": "${env.WORKSPACE}/packaging/bin/fio-bco-import-linux-(*)",
                                "target": "generic-local/openbank/fio-bco/linux/{1}/${env.VERSION}/fio-bco-import",
                                "recursive": "false"
                            },
                            {
                                "pattern": "${env.WORKSPACE}/packaging/bin/fio-bco_(*)_(*).deb",
                                "target": "generic-local/openbank/fio-bco/linux/{2}/{1}/fio-bco.deb",
                                "recursive": "false"
                            }
                        ]
                    }
                    """
                }
            }
        }
    }

    post {
        always {
            script {
                publishHTML(target: [
                    alwaysLinkToLastBuild: false,
                    keepAll: true,
                    reportDir: "${env.WORKSPACE}/reports/unit-tests/fio-bco-import",
                    reportFiles: 'fio-bco-import-coverage.html',
                    reportName: 'Unit Test Coverage (Fio BCO Import)'
                ])
                publishHTML(target: [
                    alwaysLinkToLastBuild: false,
                    keepAll: true,
                    reportDir: "${env.WORKSPACE}/reports/unit-tests/fio-bco-rest",
                    reportFiles: 'fio-bco-rest-coverage.html',
                    reportName: 'Unit Test Coverage (Fio BCO Rest)'
                ])
                cucumber(
                    reportTitle: 'Black Box Test',
                    fileIncludePattern: '*',
                    jsonReportDirectory: "${env.WORKSPACE}/reports/blackbox-tests/cucumber"
                )
            }
        }
        success {
            cleanWs()
        }
        failure {
            dir("${env.WORKSPACE}/reports") {
                archiveArtifacts(
                    allowEmptyArchive: true,
                    artifacts: 'blackbox-tests/**/*.log'
                )
            }
            cleanWs()
        }
    }
}
