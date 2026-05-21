# Deployment Guide

This document describes the CI/CD deployment process for the application.

## Overview

The application uses GitHub Actions for automated deployment to two separate environments, each with its own workflow file:

- **Staging** (`.github/workflows/staging-deploy.yml`): Automatically deploys when pushing to `release/staging` branch
- **Production** (`.github/workflows/production-deploy.yml`): Automatically deploys when pushing to `feature/finance-guru` branch

Each environment has independent deployment pipelines, allowing you to:
- Modify staging deployment without affecting production
- Test deployment changes in staging first
- Have different deployment strategies per environment

## Required GitHub Secrets

You need to configure the following secrets in your GitHub repository settings (Settings > Secrets and variables > Actions):

### Staging Environment Secrets

| Secret Name | Description | Example Value |
|------------|-------------|---------------|
| `STAGING_SERVER_HOST` | IP address or hostname of the staging server | `staging.example.com` or `192.168.1.100` |
| `STAGING_SERVER_USER` | SSH username for staging server | `staging` |
| `STAGING_SERVER_SSH_KEY` | Private SSH key for authentication to staging server | (Your SSH private key content) |

### Production Environment Secrets

| Secret Name | Description | Example Value |
|------------|-------------|---------------|
| `SERVER_HOST` | IP address or hostname of the production server | `production.example.com` or `192.168.1.200` |
| `SERVER_USER` | SSH username for production server | `production` |
| `SERVER_SSH_KEY` | Private SSH key for authentication to production server | (Your SSH private key content) |

## How to Add Secrets

1. Go to your GitHub repository
2. Click on **Settings** > **Secrets and variables** > **Actions**
3. Click **New repository secret**
4. Enter the secret name and value
5. Click **Add secret**

## Deployment Process

The deployment workflow performs the following steps:

1. **Build**: Compiles the application using Gradle and creates a bootJar
2. **Copy**: Transfers the JAR file to the target server using SCP
3. **Deploy**: SSHs into the server and restarts the `dolfin-app.service`
4. **Verify**: Shows recent application logs and checks for errors

## Server Requirements

Both staging and production servers must have:
- The same folder structure: `/home/<username>/server/`
- A systemd service named `dolfin-app.service`
- The SSH user must have sudo permissions to restart the service
- Java 21 runtime installed

## Workflow Files

### Staging Workflow (`.github/workflows/staging-deploy.yml`)
- **Trigger**: Push to `release/staging` branch
- **Target**: Staging server (uses `STAGING_SERVER_*` secrets)
- **Purpose**: Test changes before production deployment

### Production Workflow (`.github/workflows/production-deploy.yml`)
- **Trigger**: Push to `feature/finance-guru` branch
- **Target**: Production server (uses `SERVER_*` secrets)
- **Purpose**: Deploy stable releases to production

Both workflows follow the same deployment steps but target different servers based on their configured secrets.

## SSH Key Generation

If you need to generate a new SSH key pair for the servers:

```bash
# Generate a new SSH key pair
ssh-keygen -t ed25519 -C "github-actions" -f ~/.ssh/github-actions

# Copy the public key to the server
ssh-copy-id -i ~/.ssh/github-actions.pub staging@<staging-server-ip>

# Display the private key to add as a GitHub secret
cat ~/.ssh/github-actions
```

Then add the private key content to the appropriate GitHub secret (`STAGING_SERVER_SSH_KEY` or `SERVER_SSH_KEY`).

## Testing Deployment

To test the deployment:

1. Make a change in your code
2. Commit and push to the appropriate branch:
   - Push to `release/staging` for staging deployment
   - Push to `feature/finance-guru` for production deployment
3. Go to GitHub Actions tab to monitor the deployment progress
4. Check the logs for any errors

## Troubleshooting

If deployment fails:

1. Check the GitHub Actions logs for error messages
2. Verify all secrets are configured correctly
3. Ensure the SSH key has proper permissions on the server
4. Verify the systemd service name matches on both servers
5. Check that the target directory exists on the server
