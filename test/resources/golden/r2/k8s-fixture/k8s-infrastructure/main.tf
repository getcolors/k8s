terraform {
  required_version = ">= 1.8.0"
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "1.52.0"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "4.52.5"
    }
    talos = {
      source  = "siderolabs/talos"
      version = "0.9.0"
    }
  }
}

provider "hcloud" {}
provider "cloudflare" {}
provider "talos" {}

locals {
  cluster_name              = "k8s-fixture"
  private_cidr              = "10.0.1.0/24"
  private_prefix_length     = split("/", local.private_cidr)[1]
  api_private_ip            = cidrhost(local.private_cidr, 5)
  control_plane_private_ips = [for i in range(3) : cidrhost(local.private_cidr, 10 + i)]
  worker_private_ips        = [for i in range(3) : cidrhost(local.private_cidr, 20 + i)]
  talos_version_label       = replace("v1.13.7", ".", "-")
  image_selector            = "colors-package=k8s,colors-profile=k8s-fixture,talos-version=${local.talos_version_label}"
  talos_schematic           = "${data.hcloud_image.talos.labels["talos-schematic-a"]}${data.hcloud_image.talos.labels["talos-schematic-b"]}"
  installer_image           = "factory.talos.dev/installer/${local.talos_schematic}:v1.13.7"
}

data "hcloud_image" "talos" {
  with_selector = local.image_selector
  most_recent   = true
}

resource "hcloud_network" "cluster" {
  name     = "${local.cluster_name}-network"
  ip_range = "10.0.0.0/16"
  lifecycle { prevent_destroy = true }
}

resource "hcloud_network_subnet" "nodes" {
  network_id   = hcloud_network.cluster.id
  type         = "cloud"
  network_zone = "eu-central"
  ip_range     = local.private_cidr
  lifecycle { prevent_destroy = true }
}

resource "hcloud_server" "control_plane" {
  count                    = 3
  name                     = "${local.cluster_name}-control-plane-${count.index + 1}"
  image                    = data.hcloud_image.talos.id
  server_type              = "cx23"
  location                 = "fsn1"
  shutdown_before_deletion = true
  public_net {
    ipv4_enabled = true
    ipv6_enabled = false
  }
  labels = {
    "colors-package" = "k8s"
    "colors-profile" = "k8s-fixture"
    "node-role"      = "control-plane"
  }
  lifecycle {
    prevent_destroy = true
    # hcloud can omit configured location from state after a partially failed
    # parallel create; retaining it would propose destructive replacements.
    ignore_changes = [location]
  }
}

resource "hcloud_server_network" "control_plane" {
  count      = 3
  server_id  = hcloud_server.control_plane[count.index].id
  network_id = hcloud_network.cluster.id
  ip         = local.control_plane_private_ips[count.index]
  depends_on = [hcloud_network_subnet.nodes]
  lifecycle { prevent_destroy = true }
}

resource "hcloud_server" "worker" {
  count                    = 3
  name                     = "${local.cluster_name}-worker-${count.index + 1}"
  image                    = data.hcloud_image.talos.id
  server_type              = "cx23"
  location                 = "fsn1"
  shutdown_before_deletion = true
  public_net {
    ipv4_enabled = true
    ipv6_enabled = false
  }
  labels = {
    "colors-package" = "k8s"
    "colors-profile" = "k8s-fixture"
    "node-role"      = "worker"
  }
  lifecycle {
    prevent_destroy = true
    # See the control-plane server lifecycle above.
    ignore_changes = [location]
  }
}

resource "hcloud_server_network" "worker" {
  count      = 3
  server_id  = hcloud_server.worker[count.index].id
  network_id = hcloud_network.cluster.id
  ip         = local.worker_private_ips[count.index]
  depends_on = [hcloud_network_subnet.nodes]
  lifecycle { prevent_destroy = true }
}

resource "hcloud_firewall" "control_plane" {
  name = "${local.cluster_name}-control-plane"
  rule {
    direction  = "in"
    protocol   = "icmp"
    source_ips = ["203.0.113.10/32", local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "50000"
    source_ips = ["203.0.113.10/32", local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "50001"
    source_ips = [local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "6443"
    source_ips = ["203.0.113.10/32", local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "2379-2380"
    source_ips = [local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "10250"
    source_ips = [local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "4240"
    source_ips = [local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "udp"
    port       = "8472"
    source_ips = [local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "udp"
    port       = "51871"
    source_ips = [local.private_cidr]
  }
  lifecycle { prevent_destroy = true }
}

resource "hcloud_firewall_attachment" "control_plane" {
  firewall_id = hcloud_firewall.control_plane.id
  server_ids  = hcloud_server.control_plane[*].id
  lifecycle { prevent_destroy = true }
}

resource "hcloud_firewall" "worker" {
  name = "${local.cluster_name}-worker"
  rule {
    direction  = "in"
    protocol   = "icmp"
    source_ips = ["203.0.113.10/32", local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "50000"
    source_ips = ["203.0.113.10/32", local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "10250"
    source_ips = [local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "4240"
    source_ips = [local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "32080"
    source_ips = [local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "32443"
    source_ips = [local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "udp"
    port       = "8472"
    source_ips = [local.private_cidr]
  }
  rule {
    direction  = "in"
    protocol   = "udp"
    port       = "51871"
    source_ips = [local.private_cidr]
  }
  lifecycle { prevent_destroy = true }
}

resource "hcloud_firewall_attachment" "worker" {
  firewall_id = hcloud_firewall.worker.id
  server_ids  = hcloud_server.worker[*].id
  lifecycle { prevent_destroy = true }
}

# The highly available API endpoint is private. Operators use the first control
# plane public address through the generated public kubeconfig; its firewall
# admits 6443 only from admin-cidr. Nodes resolve the hostname to this LB.
resource "hcloud_load_balancer" "api" {
  name               = "${local.cluster_name}-api"
  load_balancer_type = "lb11"
  location           = "fsn1"
  algorithm { type = "round_robin" }
  lifecycle { prevent_destroy = true }
}

resource "hcloud_load_balancer_network" "api" {
  load_balancer_id        = hcloud_load_balancer.api.id
  network_id              = hcloud_network.cluster.id
  ip                      = local.api_private_ip
  enable_public_interface = false
  depends_on              = [hcloud_network_subnet.nodes]
  lifecycle { prevent_destroy = true }
}

resource "hcloud_load_balancer_target" "control_plane" {
  count            = 3
  type             = "server"
  load_balancer_id = hcloud_load_balancer.api.id
  server_id        = hcloud_server.control_plane[count.index].id
  use_private_ip   = true
  depends_on       = [hcloud_server_network.control_plane]
  lifecycle { prevent_destroy = true }
}

resource "hcloud_load_balancer_service" "api" {
  load_balancer_id = hcloud_load_balancer.api.id
  protocol         = "tcp"
  listen_port      = 6443
  destination_port = 6443
  health_check {
    protocol = "tcp"
    port     = 6443
    interval = 10
    timeout  = 5
    retries  = 3
  }
  lifecycle { prevent_destroy = true }
}

resource "hcloud_load_balancer" "ingress" {
  name               = "${local.cluster_name}-ingress"
  load_balancer_type = "lb11"
  location           = "fsn1"
  algorithm { type = "round_robin" }
  lifecycle { prevent_destroy = true }
}

resource "hcloud_load_balancer_network" "ingress" {
  load_balancer_id = hcloud_load_balancer.ingress.id
  network_id       = hcloud_network.cluster.id
  depends_on       = [hcloud_network_subnet.nodes]
  lifecycle { prevent_destroy = true }
}

resource "hcloud_load_balancer_target" "worker" {
  count            = 3
  type             = "server"
  load_balancer_id = hcloud_load_balancer.ingress.id
  server_id        = hcloud_server.worker[count.index].id
  use_private_ip   = true
  depends_on       = [hcloud_server_network.worker]
  lifecycle { prevent_destroy = true }
}

resource "hcloud_load_balancer_service" "http" {
  load_balancer_id = hcloud_load_balancer.ingress.id
  protocol         = "tcp"
  listen_port      = 80
  destination_port = 32080
  health_check {
    protocol = "tcp"
    port     = 32080
    interval = 10
    timeout  = 5
    retries  = 3
  }
  lifecycle { prevent_destroy = true }
}

resource "hcloud_load_balancer_service" "https" {
  load_balancer_id = hcloud_load_balancer.ingress.id
  protocol         = "tcp"
  listen_port      = 443
  destination_port = 32443
  health_check {
    protocol = "tcp"
    port     = 32443
    interval = 10
    timeout  = 5
    retries  = 3
  }
  lifecycle { prevent_destroy = true }
}

data "cloudflare_zone" "cluster" {
  name = "fixture.example"
}

resource "cloudflare_record" "api" {
  zone_id = data.cloudflare_zone.cluster.id
  name    = "api.k8s.fixture.example"
  content = local.api_private_ip
  type    = "A"
  ttl     = 60
  proxied = false
  lifecycle { prevent_destroy = true }
}

resource "talos_machine_secrets" "cluster" {
  talos_version = "v1.13.7"
}

locals {
  common_patch = {
    machine = {
      install = { image = local.installer_image }
      kubelet = {
        nodeIP = { validSubnets = [local.private_cidr] }
      }
    }
    cluster = {
      network = { cni = { name = "none" } }
      proxy   = { disabled = true }
      externalCloudProvider = {
        enabled   = true
        manifests = []
      }
      apiServer = {
        certSANs = concat(
          ["api.k8s.fixture.example", local.api_private_ip],
          hcloud_server.control_plane[*].ipv4_address
        )
      }
    }
  }
}

data "talos_machine_configuration" "control_plane" {
  count              = 3
  cluster_name       = local.cluster_name
  cluster_endpoint   = "https://api.k8s.fixture.example:6443"
  machine_type       = "controlplane"
  machine_secrets    = talos_machine_secrets.cluster.machine_secrets
  talos_version      = "v1.13.7"
  kubernetes_version = "v1.36.3"
  config_patches = [yamlencode(merge(local.common_patch, {
    machine = merge(local.common_patch.machine, {
      nodeLabels = { "node.kubernetes.io/role" = "control-plane" }
      nodeAddress = { validSubnets = [local.private_cidr] }
      network = {
        interfaces = [{
          interface = "eth1"
          dhcp      = false
          addresses = ["${local.control_plane_private_ips[count.index]}/${local.private_prefix_length}"]
        }]
      }
    })
    cluster = merge(local.common_patch.cluster, {
      etcd = { advertisedSubnets = [local.private_cidr] }
    })
  }))]
}

resource "talos_machine_configuration_apply" "control_plane" {
  count                       = 3
  client_configuration        = talos_machine_secrets.cluster.client_configuration
  machine_configuration_input = data.talos_machine_configuration.control_plane[count.index].machine_configuration
  node                        = hcloud_server.control_plane[count.index].ipv4_address
  endpoint                    = hcloud_server.control_plane[count.index].ipv4_address
  depends_on = [
    hcloud_server_network.control_plane,
    hcloud_firewall_attachment.control_plane,
    cloudflare_record.api,
    hcloud_load_balancer_service.api
  ]
}

data "talos_machine_configuration" "worker" {
  count              = 3
  cluster_name       = local.cluster_name
  cluster_endpoint   = "https://api.k8s.fixture.example:6443"
  machine_type       = "worker"
  machine_secrets    = talos_machine_secrets.cluster.machine_secrets
  talos_version      = "v1.13.7"
  kubernetes_version = "v1.36.3"
  config_patches = [yamlencode(merge(local.common_patch, {
    machine = merge(local.common_patch.machine, {
      nodeLabels = { "node.kubernetes.io/role" = "worker" }
      network = {
        interfaces = [{
          interface = "eth1"
          dhcp      = false
          addresses = ["${local.worker_private_ips[count.index]}/${local.private_prefix_length}"]
        }]
      }
    })
  }))]
}

resource "talos_machine_configuration_apply" "worker" {
  count                       = 3
  client_configuration        = talos_machine_secrets.cluster.client_configuration
  machine_configuration_input = data.talos_machine_configuration.worker[count.index].machine_configuration
  node                        = hcloud_server.worker[count.index].ipv4_address
  endpoint                    = hcloud_server.worker[count.index].ipv4_address
  depends_on = [
    hcloud_server_network.worker,
    hcloud_firewall_attachment.worker,
    cloudflare_record.api,
    hcloud_load_balancer_service.api
  ]
}

resource "talos_machine_bootstrap" "cluster" {
  node                 = hcloud_server.control_plane[0].ipv4_address
  endpoint             = hcloud_server.control_plane[0].ipv4_address
  client_configuration = talos_machine_secrets.cluster.client_configuration
  depends_on = [
    talos_machine_configuration_apply.control_plane,
    talos_machine_configuration_apply.worker
  ]
}

data "talos_client_configuration" "cluster" {
  cluster_name         = local.cluster_name
  client_configuration = talos_machine_secrets.cluster.client_configuration
  endpoints            = hcloud_server.control_plane[*].ipv4_address
}

resource "talos_cluster_kubeconfig" "cluster" {
  client_configuration = talos_machine_secrets.cluster.client_configuration
  node                 = hcloud_server.control_plane[0].ipv4_address
  endpoint             = hcloud_server.control_plane[0].ipv4_address
  depends_on           = [talos_machine_bootstrap.cluster]
}

# The cluster advertises its private HA endpoint. Only the ephemeral operator
# kubeconfig is rewritten to the first public control-plane address.
output "talosconfig" {
  value     = data.talos_client_configuration.cluster.talos_config
  sensitive = true
}
output "kubeconfig" {
  value = replace(
    talos_cluster_kubeconfig.cluster.kubeconfig_raw,
    "https://api.k8s.fixture.example:6443",
    "https://${hcloud_server.control_plane[0].ipv4_address}:6443"
  )
  sensitive = true
}
output "network_id" {
  value = hcloud_network.cluster.id
}
output "ingress_ipv4" {
  value = hcloud_load_balancer.ingress.ipv4
}
output "control_plane_ipv4" {
  value = hcloud_server.control_plane[*].ipv4_address
}
output "worker_ipv4" {
  value = hcloud_server.worker[*].ipv4_address
}
output "control_plane_private_ips" {
  value = local.control_plane_private_ips
}
output "worker_private_ips" {
  value = local.worker_private_ips
}
