
# Deployment to Red Hat Code Ready Containers

Instructions for deploying to Red Hat Code Ready Containers environment.

## Install Code Ready Containers

Follow the Getting Started Guide in the [Product Documentation for Red Hat CodeReady Containers](https://access.redhat.com/documentation/en-us/red_hat_codeready_containers/1.17/).

#### Configuring the virtual machine

It may be necessary to adjust the CRC CPU and memory settings to allow for suficient capacity to run the demo application. Use the following commands to adjust the configuration or to set the CPU and memory allocations when CRC is started.

To configure the number of vCPUs available to the virtual machine:

~~~bash
$ crc config set cpus <number>
~~~

The default value for the cpus property is 4. The number of vCPUs to assign must be greater than or equal to the default.

To start the virtual machine with the desired number of vCPUs:

~~~bash
$ crc start --cpus <number>
~~~

To configure the memory available to the virtual machine:

~~~bash
$ crc config set memory <number-in-mib>
~~~

NOTE
Values for available memory are set in mebibytes (MiB). One gibibyte (GiB) of memory is equal to 1024 MiB.

The default value for the memory property is 9216. The amount of memory to assign must be greater than or equal to the default.

To start the virtual machine with the desired amount of memory:

~~~bash
$ crc start --memory <number-in-mib>
~~~

#### Create CRC Project

Get the login credentials using the following command.

~~~bash
$ crc console --credentials
~~~

Login as user `developer`.

~~~bash
$ oc login -u developer -p developer https://api.crc.testing:6443
~~~

If not already created, create a project. This creates a Kubernetes namespace with the same name.

~~~bash
$ oc new-project simulator
~~~

#### Clone the GitHub Repo

Git clone the project repoisitory.

~~~bash
$ git clone https://github.com/mckeeh3/akka-cluster-kubernetes-simulator.git
~~~

#### Build and Upload the Docker Image

After cloning the project, `cd` into the project directory then build the Docker image.

~~~bash
$ mvn clean package docker:build
~~~

Next, tag and push the image to the public Docker repo. This requires that you have a Docker account.

~~~bash
$ docker tag akka-k8-simulator <your-docker-username>/akka-k8-simulator:latest

...

$ docker push <your-docker-username>/akka-k8-simulator
~~~

#### Deploy to Kubernetes

First, edit the `kubernetes/akka-cluster-red-hat-crc.yml` file changing the Docker username.

~~~
image: <your-docker-username>/akka-k8-simulator:latest
~~~

Then deploy the microservice.

~~~bash
kubectl apply -f kubernetes/akka-cluster-red-hat-crc.yml
~~~
~~~
deployment.apps/woe-twin created
role.rbac.authorization.k8s.io/pod-reader created
rolebinding.rbac.authorization.k8s.io/read-pods created
~~~

Check if the pods are running. This may take a few moments.

~~~bash
$ kubectl get pods                                          
~~~
~~~
NAME                         READY   STATUS    RESTARTS   AGE
simulator-5b4cf87d4d-5b7w6   1/1     Running   0          3m31s
simulator-5b4cf87d4d-hzp7n   1/1     Running   0          3m31s
simulator-5b4cf87d4d-pbst2   1/1     Running   0          3m31s
~~~

