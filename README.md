# git-motel

![alt text][https://github.com/ingenieux/git-motel/blob/master/imgs/git-motel.jpg]

## What is it?

There are too many services which rely too much on git. 

If you don't use git, or instead rely on a compiled language, chances are that you're p*ssed off with either
Heroku, and/or Quay.io.

git-motel is an AWS Lambda Handler which, once a file is saved on S3, allows you to push into a git repository.

This way, your builds can simply upload an archive to S3, and it does the rest.

# Using it

First, edit deploy.sh to set your defaults, then run deploy.sh to get it deployed into S3 (note it requires Maven)

Then, edit the observed bucket and set either the bucket tags or the object metadata, where:

  * git_key (defaults to 'id_rsa'): SSH Key to Use
  * git_branch (defaults to object key basename): Git branch to push to. Use 'commitId' to use commitId as the branch name instead
  * git_repository: Remote Git Repository (like git@bitbucket.org:user/name.git)

Then, map the lambda function to the observed s3 repository. 

Note that you need at least s3:get* to work. logs:* would be useful to get the runtime logs.

You can also test the usage. For this, simulate a S3 Event on the lambda console, put place dryRun set to true on its root object payload. e.g.:

```
{
  "dryRun": true,
  "Records": [
    {
      "eventVersion": "2.0",
      "eventSource": "aws:s3",
      "awsRegion": "us-east-1",
      "eventTime": "1970-01-01T00:00:00.000Z",
      "eventName": "ObjectCreated:Put",
      "userIdentity": {
        "principalId": "EXAMPLE"
      },
      "requestParameters": {
        "sourceIPAddress": "127.0.0.1"
      },
      "responseElements": {
        "x-amz-request-id": "C3D13FE58DE4C810",
        "x-amz-id-2": "FMyUVURIY8/IgAtTv8xRjskZQpcIZ9KG4V5Wp6S7S/JRWeUWerMUE5JgHvANOjpD"
      },
      "s3": {
        "s3SchemaVersion": "1.0",
        "configurationId": "testConfigRule",
        "bucket": {
          "name": "ingenieux-gitmotel",
          "ownerIdentity": {
            "principalId": "EXAMPLE"
          },
          "arn": "arn:aws:s3:::mybucket"
        },
        "object": {
          "key": "sample-docker-nodejs/app.tar.gz",
          "size": 1024,
          "eTag": "d41d8cd98f00b204e9800998ecf8427e"
        }
      }
    }
  ]
}
```
