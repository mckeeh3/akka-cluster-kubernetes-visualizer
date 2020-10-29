
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
$ oc new-project visualizer
~~~

#### Clone the GitHub Repoitory

Git clone the project repoisitory.

~~~bash
$ git clone https://github.com/mckeeh3/akka-cluster-kubernetes-visualizer.git
~~~

#### Build and Upload the Docker Image

After cloning the project, `cd` into the project directory then build the Docker image.

~~~bash
$ mvn clean package docker:build
~~~

Next, tag and push the image to the public Docker repo. This requires that you have a Docker account.

~~~bash
$ docker tag akka-k8-visualizer <your-docker-username>/akka-k8-visualizer:latest

...

$ docker push <your-docker-username>/akka-k8-visualizer
~~~

#### Deploy to Kubernetes

First, edit the `kubernetes/akka-cluster-red-hat-crc.yml` file changing the Docker username.

~~~
image: <your-docker-username>/akka-k8-visualizer:latest
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
visualizer-5b4cf87d4d-5b7w6   1/1     Running   0          3m31s
visualizer-5b4cf87d4d-hzp7n   1/1     Running   0          3m31s
visualizer-5b4cf87d4d-pbst2   1/1     Running   0          3m31s
~~~

#### Enable External Access

Create a load balancer to enable access to the WOE Twin microservice HTTP endpoint.

~~~bash
$ kubectl expose deployment visualizer --type=LoadBalancer --name=visualizer-service
~~~
~~~
service/visualizer-service exposed
~~~

Next, view to external port assignments.

~~~bash
$ kubectl get services visualizer-service                                           
~~~
~~~
NAME                         TYPE           CLUSTER-IP       EXTERNAL-IP   PORT(S)                                        AGE
service/visualizer-service   LoadBalancer   172.25.134.107   <pending>     2552:31672/TCP,8558:31661/TCP,8080:30359/TCP   7s
~~~

Note that in this example, the Kubernetes internal port 8080 external port assignment of 30359.

For CRC deployments, the full URL to access the HTTP endpoint is constructed using the CRC IP and the external port.

~~~bash
$ crc ip       
~~~
In this example the CRC IP is:
~~~
192.168.64.3
~~~
Try accessing this endpoint using the curl command or from a browser. Use the external port defined for port 8080. In the example above the external port for port 8080 is 30359.

From a browser use the IP and port to create a URL to the cluster sharding viewer: `http://192.168.64.3:30359`


