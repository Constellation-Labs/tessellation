terraform {
  backend "s3" {
    bucket = "constellationlabs-tf"
    key    = "apidoc"
  }
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.8.0"
    }
    null-resource = {
      source  = "hashicorp/null"
      version = "~> 3.1.1"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

module "bucket" {
  source = "./modules/bucket"
  bucket = var.bucket
}


# DAG

module "openapi_dag_node_public_l0" {
  source = "./modules/openapi"

  s3_bucket_name  = module.bucket.bucket
  s3_prefix       = "dag/l0/public"
  s3_default_file = "public.yml"
  spec_path       = "${path.cwd}/../dag/l0"
}

module "openapi_dag_node_public_l1" {
  source = "./modules/openapi"

  s3_bucket_name  = module.bucket.bucket
  s3_prefix       = "dag/l1/public"
  s3_default_file = "public.yml"
  spec_path       = "${path.cwd}/../dag/l1"
}

module "openapi_dag_node_owner" {
  source = "./modules/openapi"

  s3_bucket_name  = module.bucket.bucket
  s3_prefix       = "dag/cli"
  s3_default_file = "cli.yml"
  spec_path       = "${path.cwd}/../dag/cli"
}

# Currency

module "openapi_currency_node_public_l0" {
  source = "./modules/openapi"

  s3_bucket_name  = module.bucket.bucket
  s3_prefix       = "currency/v${var.currency_version}/l0/public"
  s3_default_file = "public.yml"
  spec_path       = "${path.cwd}/../currency/l0"
}

module "openapi_currency_node_public_l1" {
  source = "./modules/openapi"

  s3_bucket_name  = module.bucket.bucket
  s3_prefix       = "currency/v${var.currency_version}/l1/public"
  s3_default_file = "public.yml"
  spec_path       = "${path.cwd}/../currency/l1"
}

module "openapi_currency_node_public_l1_data" {
  source = "./modules/openapi"

  s3_bucket_name  = module.bucket.bucket
  s3_prefix       = "currency/v${var.currency_version}/l1-data/public"
  s3_default_file = "public.yml"
  spec_path       = "${path.cwd}/../currency/l1-data"
}

module "openapi_currency_node_owner" {
  source = "./modules/openapi"

  s3_bucket_name  = module.bucket.bucket
  s3_prefix       = "currency/v${var.currency_version}/cli"
  s3_default_file = "cli.yml"
  spec_path       = "${path.cwd}/../currency/cli"
}
