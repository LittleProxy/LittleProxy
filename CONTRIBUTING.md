
Contribution guide for LittleProxy

## How to start

    git clone git@github.com:LittleProxy/LittleProxy.git
    cd LittleProxy
    mvn test

## Release

* Update the release notes (file README.md)
* Change version in the pom.xml file (e.g. "2.4.5-SNAPSHOT" -> "2.4.5") 
* Change version in README.md (e.g. "2.4.4" -> "2.4.5")
* Run `mvn clean install`
* Run `deploy.bash` (takes a while, showing "Waiting until Deployment **** is published")
* Log into https://central.sonatype.com/publishing/deployments and ensure that the deployment status is "Published".
* Commit the changes (e.g. with message "release LittleProxy 2.4.5") and make sure the CI build passes
* Run `git tag v2.4.5 && git push --tags`
* Create a new release in GitHub (go to `https://github.com/LittleProxy/LittleProxy/releases/new`)
* Update the version in the pom.xml file to the next SNAPSHOT version (e.g. "2.4.5" -> "2.4.6-SNAPSHOT").
* Commit the pom.xml change (e.g. with commit message "working on LittleProxy 2.4.6")
* Announce the release in https://groups.google.com/forum/#!forum/littleproxy2
