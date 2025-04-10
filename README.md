# Replica's Fork of "GraphHopper Main"

This repo serves as Replica's copy of the main GraphHopper repo, without the heavy modifications that we've applied in our existing `graphhopper` repo fork (https://github.com/replicahq/graphhopper).

The purpose of this `graphhopper_main` fork is to allow us to more easily modify internal details of GraphHopper that lie outside of the files we include in our existing `graphhopper` fork. That repo now points to this as
its upstream dependency, rather than pointing directly to a more officially-released version of GraphHopper.

Note: we use the `patterns` branch as our main branch in this repo; this is where the new transit algorithm was developed (it hasn't yet been merged to the main GraphHopper repo's `master` branch).

### Making edits in this repo

Once you've cloned this repo locally, ensure that you're on the `patterns` branch before proceeding.
Currently, edits can be made by pushing directly to this branch, although any larger edits should go through the pull request/code review process before being merged to `patterns`.

Once you've incorporated your changes in the `patterns` branch, you can package your project locally with `mvn clean package -DskipTests`.

Then, you can deploy your newly-packaged JARs to our GCS-backed maven repository with `mvn clean deploy -P replica-repo -DskipTests`. Deployed JARs are stored in the `model-maven-repo` GCS bucket.
This step makes your local code accessible by Replica's main `graphhopper` repo.

Note: the deploy process is currently set up to override existing JARs when a new deploy occurs, because the location in GCS where the deployed artifacts are placed is based on the maven `version` tag 
defined in each pomfile, which is static. Therefore, if you're hoping to test out changes to this repo prior to incorporating them into Replica's `graphhopper` repo, ensure that the `version` tag is
updated in the parent `pom.xml`, as well as each submodule's `pom.xml` file, prior to running the deploy command (the version tag looks like this: `<version>8.0-SNAPSHOT</version>`).
Then, similar changes can be made to the `version` tags in each pomfile in Replica's `graphhopper` repo to utilize this "test deploy" as an upstream dependency there.
