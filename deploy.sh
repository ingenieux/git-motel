#!/bin/bash

set -x

ROLE=arn:aws:iam::235368163414:role/lambda_basic_execution
BUCKET=ingenieux-images
KEY=/git-motel-java/git-model-java.zip

mvn clean package -Pdeploy

aws s3 cp target/git-motel-java.jar s3://$BUCKET/$KEY

aws lambda update-function-code --function-name git-motel --s3-bucket $BUCKET --s3-key $KEY ||
  aws lambda create-function --function-name git-motel --runtime java8 --role $ROLE --handler io.ingenieux.GitUploader::gitUploadHandler --code S3Bucket=$BUCKET,S3Key=$KEY --timeout 60 --memory-size 128
