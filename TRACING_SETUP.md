# Distributed Tracing Setup Guide

This guide documents the complete setup for distributed tracing in the Pizza application using Jaeger, OpenTelemetry Collector, and Dapr.

## Prerequisites

- Kubernetes cluster running
- Helm installed
- kubectl configured

## 1. Install cert-manager (Required for Jaeger Operator)

```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.2/cert-manager.yaml
```

Wait for cert-manager to be ready:
```bash
kubectl wait --for=condition=available deployment/cert-manager -n cert-manager --timeout=300s
kubectl wait --for=condition=available deployment/cert-manager-webhook -n cert-manager --timeout=300s
```

## 2. Install Jaeger

### Add Jaeger Helm repository
```bash
helm repo add jaegertracing https://jaegertracing.github.io/helm-charts
helm repo update
```

### Install Jaeger Operator
```bash
helm install jaeger-operator jaegertracing/jaeger-operator
```

### Deploy Jaeger All-in-One Instance
```bash
kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jaeger
  namespace: default
  labels:
    app: jaeger
    app.kubernetes.io/name: jaeger
spec:
  replicas: 1
  selector:
    matchLabels:
      app: jaeger
  template:
    metadata:
      labels:
        app: jaeger
        app.kubernetes.io/name: jaeger
    spec:
      containers:
      - name: jaeger
        image: jaegertracing/all-in-one:latest
        ports:
        - containerPort: 16686
        - containerPort: 14268
        - containerPort: 4317
        - containerPort: 4318
        env:
        - name: COLLECTOR_OTLP_ENABLED
          value: "true"
---
apiVersion: v1
kind: Service
metadata:
  name: jaeger-query
  namespace: default
spec:
  selector:
    app: jaeger
  ports:
  - name: query-http
    port: 16686
    targetPort: 16686
---
apiVersion: v1
kind: Service
metadata:
  name: jaeger-collector
  namespace: default
spec:
  selector:
    app: jaeger
  ports:
  - name: jaeger-collector-http
    port: 14268
    targetPort: 14268
  - name: jaeger-collector-grpc
    port: 4317
    targetPort: 4317
  - name: jaeger-collector-http-otlp
    port: 4318
    targetPort: 4318
EOF
```

### Wait for Jaeger to be ready
```bash
kubectl wait deploy/jaeger --for=condition=available --timeout=300s
```

## 3. Deploy OpenTelemetry Collector

```bash
kubectl apply -f otel-collector-jaeger.yaml
```

Wait for OTel Collector to be ready:
```bash
kubectl wait deploy/otel-collector --for=condition=available --timeout=120s
```

## 4. Configure Dapr Tracing

### Apply Dapr tracing configuration
```bash
kubectl apply -f dapr-tracing-config.yaml
```

### Add tracing annotations to deployments
```bash
# Add tracing annotation to pizza-store deployment
kubectl patch deployment pizza-store-deployment -p '{"spec":{"template":{"metadata":{"annotations":{"dapr.io/config":"tracing"}}}}}'

# Add tracing annotation to kitchen-service deployment  
kubectl patch deployment pizza-kitchen-deployment -p '{"spec":{"template":{"metadata":{"annotations":{"dapr.io/config":"tracing"}}}}}'

# Add tracing annotation to delivery-service deployment
kubectl patch deployment pizza-delivery-deployment -p '{"spec":{"template":{"metadata":{"annotations":{"dapr.io/config":"tracing"}}}}}'
```

## 5. Verification

### Check all components are running
```bash
kubectl get pods | grep -E "(jaeger|otel|pizza)"
```

### Access Jaeger UI
```bash
kubectl port-forward svc/jaeger-query 16686:16686
```
Then visit: http://localhost:16686

### Check services in Jaeger
```bash
kubectl port-forward svc/jaeger-query 16686:16686 &
sleep 3
curl http://localhost:16686/api/services
```

## 6. Generate Test Traffic

To see traces, you need to trigger inter-service communication through Dapr:

```bash
# Generate test traffic to pizza-store
kubectl run test-client --image=curlimages/curl --restart=Never --rm=false -- sh -c "for i in \$(seq 1 5); do curl -s http://pizza-store.default.svc.cluster.local/ || echo 'request failed'; sleep 1; done"

# Check logs
kubectl logs test-client

# Clean up
kubectl delete pod test-client
```

## Architecture

The tracing flow is:
1. **Dapr Sidecars** → Generate traces for service invocations, pub/sub, state operations
2. **OpenTelemetry Collector** → Receives traces from Dapr via gRPC (port 4317)
3. **Jaeger** → Stores and displays traces received from OTel Collector

## Configuration Files

- `otel-collector-jaeger.yaml` - OpenTelemetry Collector configuration
- `dapr-tracing-config.yaml` - Dapr tracing configuration
- `k8s/pizza-*.yaml` - Updated with `dapr.io/config: "tracing"` annotations

## Troubleshooting

### Check Dapr tracing is enabled
```bash
kubectl logs <pod-name> -c daprd | grep -i trace
```

### Check OTel Collector logs
```bash
kubectl logs -l app=otel-collector
```

### Check Jaeger logs
```bash
kubectl logs -l app=jaeger
```

### Verify connectivity
```bash
# Test OTel Collector endpoint
kubectl run test-client --image=curlimages/curl --restart=Never --rm=false -- curl -v http://otel-collector.default.svc.cluster.local:4317

# Test Jaeger collector endpoint  
kubectl run test-client --image=curlimages/curl --restart=Never --rm=false -- curl -v http://jaeger-collector.default.svc.cluster.local:4317
```

## Notes

- Traces are generated when services communicate through **Dapr building blocks** (service invocation, pub/sub, state store operations)
- Direct HTTP requests to application endpoints may not generate traces
- Use Dapr service invocation API for guaranteed trace generation: `http://<dapr-sidecar>:3500/v1.0/invoke/<app-id>/method/<endpoint>`