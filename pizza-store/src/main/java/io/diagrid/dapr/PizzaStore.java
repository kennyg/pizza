package io.diagrid.dapr;


import java.time.Duration;
import java.util.concurrent.TimeoutException;

import io.dapr.spring.workflows.config.EnableDaprWorkflows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import io.dapr.client.DaprClient;
import io.dapr.client.DaprClientBuilder;
import io.dapr.client.domain.CloudEvent;
import io.dapr.client.domain.State;
import io.dapr.workflows.client.DaprWorkflowClient;
import io.diagrid.dapr.model.Event;
import io.diagrid.dapr.model.EventType;
import io.diagrid.dapr.model.OrderPayload;
import io.diagrid.dapr.model.Orders;
import io.diagrid.dapr.model.WorkflowPayload;
import io.diagrid.dapr.workflow.PizzaWorkflow;

@SpringBootApplication
@RestController
@EnableDaprWorkflows
public class PizzaStore {

  @Value("${dapr-http.base-url:http://localhost:3500}")
  private String daprHttp;

  @Value("${STATE_STORE_NAME:kvstore}")
  private String STATE_STORE_NAME;
  
  @Value("${PUBLIC_IP:localhost:8080}")
  private String publicIp;

  @GetMapping("/server-info")
  public Info getInfo(){
    return new Info(publicIp);
  }

  @Autowired
  private DaprWorkflowClient daprWorkflowClient;

  @Autowired
  private DaprClient daprClient;

  @Autowired
  private RestTemplate restTemplate;

  public record Info(String publicIp){}

  private String KEY = "orders";

  private final SimpMessagingTemplate simpMessagingTemplate;

  public static WorkflowPayload payload; 

  public static void main(String[] args) {
    SpringApplication.run(PizzaStore.class, args);

  }

  public PizzaStore(SimpMessagingTemplate simpMessagingTemplate) {
    this.simpMessagingTemplate = simpMessagingTemplate;
  }

  @PostMapping(path = "/events", consumes = "application/cloudevents+json")
  public void receiveEvents(@RequestBody CloudEvent<Event> event) {
    emitWSEvent(event.getData());
    System.out.println("Received CloudEvent via Subscription: " + event.toString());
    Event pizzaEvent = event.getData();
    if(pizzaEvent.type().equals(EventType.ORDER_READY)){
        // Emit Event
        Event wsevent = new Event(EventType.ORDER_OUT_FOR_DELIVERY, pizzaEvent.order(), "store", "Delivery in progress.");
        emitWSEvent(wsevent);
        daprWorkflowClient.raiseEvent(pizzaEvent.order().workflowId(), "KitchenDone", pizzaEvent.order());
    }
    if(pizzaEvent.type().equals(EventType.ORDER_COMPLETED)){
      daprWorkflowClient.raiseEvent(pizzaEvent.order().workflowId(), "PizzaDelivered", pizzaEvent.order());
    }

  }

  private void emitWSEvent(Event event) {
    System.out.println("Emitting Event via WS: " + event.toString());
    simpMessagingTemplate.convertAndSend("/topic/events",
        event);
  }

  @PostMapping("/order")
  public ResponseEntity<OrderPayload> placeOrder(@RequestBody(required = true) OrderPayload order) throws Exception {
    new Thread(new Runnable() {
      @Override
      public void run() {
        // Emit Event
        Event event = new Event(EventType.ORDER_PLACED, order, "store", "We received the payment your order is confirmed.");

        emitWSEvent(event);


        startPizzaWorkflow(order);
        
        // Store Order
        // store(order);

        // // Process Order, sent to kitcken
        // callKitchenService(order);
      }
    }).start();

    return ResponseEntity.ok(order);

  }

  private void startPizzaWorkflow(OrderPayload order){
    payload = new WorkflowPayload(order);

    String instanceId = daprWorkflowClient.scheduleNewWorkflow(PizzaWorkflow.class, payload);
    System.out.printf("scheduled new workflow instance of OrderProcessingWorkflow with instance ID: %s%n",
        instanceId);

    try {
      daprWorkflowClient.waitForInstanceStart(instanceId, Duration.ofSeconds(10), false);
      System.out.printf("workflow instance %s started%n", instanceId);
    } catch (TimeoutException e) {
      System.out.printf("workflow instance %s did not start within 10 seconds%n", instanceId);

    }

    // try {
    //   WorkflowInstanceStatus workflowStatus = workflowClient.waitForInstanceCompletion(instanceId,
    //       Duration.ofSeconds(30),
    //       true);
        
    //   if (workflowStatus != null) {
    //     System.out.printf("workflow instance completed, out is: %s%n",
    //         workflowStatus.getSerializedOutput());
    //   } else {
    //     System.out.printf("workflow instance %s not found%n", instanceId);
    //   }
    // } catch (TimeoutException e) {
    //   System.out.printf("workflow instance %s did not complete within 30 seconds%n", instanceId);
    // }

  }

  @GetMapping("/order")
  public ResponseEntity<Orders> getOrders() {

    Orders orders = loadOrders();

    return ResponseEntity.ok(orders);
  }


 

  
  // private void store(Order order) {
  //   try (DaprClient client = (new DaprClientBuilder()).build()) {
  //     Orders orders = new Orders(new ArrayList<Order>());
  //     State<Orders> ordersState = client.getState(STATE_STORE_NAME, KEY, null, Orders.class).block();
  //     if (ordersState.getValue() != null && ordersState.getValue().orders().isEmpty()) {
  //       orders.orders().addAll(ordersState.getValue().orders());
  //     }
  //     orders.orders().add(order);
  //     // Save state
  //     client.saveState(STATE_STORE_NAME, KEY, orders).block();

  //   } catch (Exception ex) {
  //     ex.printStackTrace();
  //   }

  // }

  private void callKitchenService(OrderPayload order) {
    HttpHeaders headers = new HttpHeaders();
    headers.add("Content-Type", "application/json");
    headers.add("dapr-app-id", "kitchen-service");
    HttpEntity<OrderPayload> request = new HttpEntity<OrderPayload>(order, headers);
    restTemplate.put(
        daprHttp + "/prepare", request);
  }

  private void callDeliveryService(OrderPayload order) {
    HttpHeaders headers = new HttpHeaders();
    headers.add("Content-Type", "application/json");
    headers.add("dapr-app-id", "delivery-service");
    HttpEntity<OrderPayload> request = new HttpEntity<OrderPayload>(order, headers);
    restTemplate.put(
        daprHttp + "/deliver", request);
  }

  private Orders loadOrders() {
    State<Orders> ordersState = daprClient.getState(STATE_STORE_NAME, KEY, null, Orders.class).block();
    return ordersState.getValue();
  }

}
