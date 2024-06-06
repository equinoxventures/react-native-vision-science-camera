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
- To publish to NPM we need to run the following command on terminal on root level of the project. 
- `node npm-publish.sh`
