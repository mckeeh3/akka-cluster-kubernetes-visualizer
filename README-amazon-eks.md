# Deployment to Amazon EKS

Both the simulator and visualizer projects share the same Kubernetes cluster so the following step is done once for both projects.

Go to [Getting started with eksctl](https://docs.aws.amazon.com/eks/latest/userguide/getting-started-eksctl.html)
for directions on setting up EKS and Kubernetes CLI tools.

Recommend that you create an EKS cluster with two or more Kubernetes nodes.

Once the Kubernetes cluster has been created you can use the following command to add it to your local Kubernetes configuration settings.

~~~bash
aws eks update-kubeconfig --name <your-cluster-name> [--region <your-aws-region>]
~~~

## Some Additional CLI Setup

The `kubectl` CLI provides a nice Kubectl Autocomplete feature for `bash` and `zsh`.
See the [kubectl Cheat Sheet](https://kubernetes.io/docs/reference/kubectl/cheatsheet/#kubectl-autocomplete) for instructions.

Also, consider installing [`kubectx`](https://github.com/ahmetb/kubectx), which also includes `kubens`.
Mac:

~~~bash
brew install kubectx
~~~

Arch Linux:

~~~bash
yay kubectx
~~~

You may also want to create an alias for the `kubectl` command, such as `kc`.

~~~bash
alias kc=kubectl
~~~

### Clone the GitHub Repo

Git clone the project repoisitory.

~~~bash
git clone https://github.com/mckeeh3/akka-cluster-kubernetes-visualizer.git
~~~

### Build and Upload the Docker Image

After cloning the project, `cd` into the project directory then build the Docker image.

From the `akka-cluster-kubernetes-visualizer` project directory.

~~~bash
mvn clean package
~~~

Next, tag and push the image to the public Docker repo. This requires that you have a Docker account.

~~~bash
$ docker tag akka-k8-visualizer <your-docker-username>/akka-k8-visualizer:latest

...

$ docker push <your-docker-username>/akka-k8-visualizer
~~~

### Deploy to Kubernetes

First, edit the `kubernetes/akka-cluster-amazon-eks.yml` file changing the Docker username.

~~~text
image: <your-docker-username>/akka-k8-visualizer:latest
~~~

Then deploy the microservice.

~~~bash
kubectl apply -f kubernetes/akka-cluster-amazon-eks.yml
namespace/visualizer created
deployment.apps/visualizer created
role.rbac.authorization.k8s.io/pod-reader created
rolebinding.rbac.authorization.k8s.io/read-pods created
~~~

Check if the pods are running. This may take a few moments.

~~~bash
$ kubectl get pods -n visualizer
NAME                         READY   STATUS    RESTARTS   AGE
visualizer-5b4cf87d4d-5b7w6   1/1     Running   0          3m31s
visualizer-5b4cf87d4d-hzp7n   1/1     Running   0          3m31s
visualizer-5b4cf87d4d-pbst2   1/1     Running   0          3m31s
~~~

### Scale the Akka cluster

Use the following commands to scale the Akka cluster nodes by adjusting the number of Kubernetes pods.

~~~bash
kubectl scale --replicas 5 deployment/visualizer -n visualizer
~~~

### Enable External Access

Create a load balancer to enable access to the visualizer microservice HTTP endpoint.

~~~bash
$ kubectl expose deployment visualizer --type=LoadBalancer --name=visualizer-service -n visualizer

service/visualizer-service exposed
~~~

~~~bash
$ kubectl get services visualizer-service -n visualizer
NAME                 TYPE           CLUSTER-IP     EXTERNAL-IP                                                               PORT(S)                                        AGE
visualizer-service   LoadBalancer   10.100.91.15   ad110f7c353d94e9c8642b36206a74a8-1760520412.us-east-1.elb.amazonaws.com   8080:31961/TCP,8558:32054/TCP,2552:31715/TCP   62s
~~~

### Access the visualizer web page

Use the hostname provided EXTERNAL-IP column in the above command. In this example, the host name is
`ad110f7c353d94e9c8642b36206a74a8-1760520412.us-east-1.elb.amazonaws.com.` The URL to the visualizer as follows:

~~~text
http://<EXTERNAL-IP>:8080

e.g.
http://ad110f7c353d94e9c8642b36206a74a8-1760520412.us-east-1.elb.amazonaws.com:8080
~~~
