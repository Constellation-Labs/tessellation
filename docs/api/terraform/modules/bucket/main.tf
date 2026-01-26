resource "aws_s3_bucket" "apidoc" {
  bucket = var.bucket
}

resource "aws_s3_bucket_public_access_block" "apidoc_public_access" {
  bucket = aws_s3_bucket.apidoc.id

  block_public_acls       = false
  block_public_policy     = false
  ignore_public_acls      = false
  restrict_public_buckets = false
}

resource "aws_s3_bucket_ownership_controls" "apidoc_ownership" {
  depends_on = [aws_s3_bucket_public_access_block.apidoc_public_access]
  bucket = aws_s3_bucket.apidoc.id
  rule {
    object_ownership = "ObjectWriter"
  }
}

resource "aws_s3_bucket_acl" "apidoc_acl" {
  depends_on = [aws_s3_bucket_ownership_controls.apidoc_ownership]
  bucket = aws_s3_bucket.apidoc.id
  acl    = "public-read"
}

resource "aws_s3_bucket_policy" "apidoc_policy" {
  depends_on = [aws_s3_bucket_public_access_block.apidoc_public_access]
  bucket = aws_s3_bucket.apidoc.id

  policy = <<EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "PublicReadGetObject",
            "Effect": "Allow",
            "Principal": "*",
            "Action": [
                "s3:GetObject"
            ],
            "Resource": [
                "arn:aws:s3:::${var.bucket}/*"
            ]
        }
    ]
}
EOF

}

resource "aws_s3_bucket_website_configuration" "apidoc_website" {
  bucket = aws_s3_bucket.apidoc.id

  index_document {
    suffix = "index.html"
  }

  error_document {
    key = "error.html"
  }
}
