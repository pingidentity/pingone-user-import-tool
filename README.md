# User Import Tool

## TL;DR

This tool provides a quick and easy way for users using PingOne for Customers (P14C) to import users into their application instance from a CSV file. Read more about P14C at https://developer.pingidentity.com.

To build:

    mvn clean install
    
To get usage information:

    java -jar target/user-import-tool-1.0-SNAPSHOT-jar-with-dependencies.jar -?

Your csv should contain a list of users. At a minimum, each record should have email and username attributes. An example csv is provided [here](https://github.com/pingidentity/pingone-customers-user-import-tool/blob/master/examples.csv) for your convenience. This tool also supports these attributes:

    username
    email
    primaryPhone
    mobilePhone
    name.honorificPrefix
    name.given
    name.middle
    name.family
    name.honorificSuffix
    name.formatted

**NOTE:** There must be no spaces between commas to detect the values properly.

## Prepare P14C environment and collect information.

### Task 0 - Create a free P14C Trial Account (if you don't already have one).

1. Navigate to https://www.pingidentity.com/en/try-ping.html and sign up.
2. Verify your email address.
3. Log in from https://console.pingone.com/?env={{env_id}}.

### Task 1 - Create a Worker App that will be used to provision to P14C

1. Click the Connections tab
2. Click + Application
3. Choose Worker, and click Configure.
4. Define a name and description, click Save and Close
5. You'll see your app listed. The Client ID is listed right under the name - record this.
6. Expand the new application and click the pencil icon.
7. On the Roles tab, ensure Identity Data Admin is listed for the population you'll be using. If it's not there, it's because the user who created the application doesn't have that permission. Another admin will need to login and assign this role.
8. On the Configuration Tab, click to show the Client Secret and record this.

### Task 2 - Collect information about your P14C environment.

1. Click **Environments** and then **Properties** on the left side bar.
2. Record the Environment ID.
3. Click **Identities** and then **Populations** on the left side bar. 
4. Beside the population you want to create the new users for, click the expand button and record the Population ID.

### Task 3 - Install and run the tool.

**NOTE:** Java and Maven are required to build and run this tool.
 
To confirm Java is installed, from a command prompt, type `java -version`. If you get an error, you'll need to install Java. It can be downloaded from [https://adoptopenjdk.net]. Be sure to also choose the options to Add to PATH and Set JAVA_HOME, as shown in the screenshot.

To confirm Maven is installed (only required to rebuild the project) type `mvn -version`. If you get an error, you'll need to install Java. It can be downloaded from [https://maven.apache.org/download.cgi]. Be sure to also follow the install instructions from [https://maven.apache.org/install.html].

1. Download this repository to a working directory, such as C:\P14C. From the parent folder, run the maven command to build the application `mvn clean install`
2. Copy your .csv file into the "target" folder that contains the built application.
3. Open a Command Prompt and change your working directory to the "target" folder
4. run the tool using this command:
    * If you notice a number of errors in the command window, don't kill the task, let it complete. 
    * Advanced usage details are available if you run java -jar user-import-tool-1.0-SNAPSHOT-jar-with-dependencies.jar -?

    ```
    java -jar user-import-tool-1.0-SNAPSHOT-jar-with-dependencies.jar --csvFile <YOUR CSV FILE> --environmentId <YOUR ENVIRONMENT ID> --populationId <YOUR POPULATION ID> --clientId <YOUR CLIENT ID> --clientSecret <YOUR CLIENT SECRET>
    ```

    For example:
    
    ```
    java -jar user-import-tool-1.0-SNAPSHOT-jar-with-dependencies.jar --csvFile myUserLost.csv --environmentId daa7bf58-680a-4f50-ce79-7b384afc2421 --populationId a685ed8a-0294-3db9-94be-cec6009d230d --clientId a0525221-7305-41f0-3408-d890a833b80a --clientSecret xgfujhfr5GEg5D.162UkSiJszDtnS4xUM7_EF2bM20rqzEhRxfXz5mEyoFxp
    ```

5. When the tool is finished running, it will summarize the results for you and tell you if there are any failures. If there are no failures then the users should now exist in P14C.
6. If the tool encountered any errors, it will create a new csv file called rejects.csv that contains the entry for each user that failed to import. It will also create a log file you can use to determine the failure reason. Both the rejects file and log will be created in your working directory.
7. Assuming the failure wasn't due to invalid credentials or network connectivity etc, the failures are likely due to invalid data (such as invalid phone number / email format, or password policy violations). 
8. You can modify the rejects.csv file to correct any data and re-save it, and then run the above command again but pointing to your new .csv file.
9. Repeat steps 4-8 as necessary until all users have been imported.
