### Step 1:
- Back up existing main branch

### Step 2:
Rebase off RNVC main branch
```
git remote add upstream https://github.com/mrousavy/react-native-vision-camera.git 
git fetch upstream 
git checkout main 
git rebase upstream/main 
git push -f origin main 
```

### Step 3: 
- Optional step - update the version in `package/package.json`

### Step 4:
- Login to npm with `npm login`
- To publish to NPM we need to run the following command on terminal on root level of the project.
- Get the One time password from your authenticator app with your npm login. 
- Run `node npm-publish.sh --otp=123456`

### Step 5:
- remove temporary changes to `package/package.json`
- run `git checkout package/package.json`
