terraform {
  required_version = ">= 1.8.0"
  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "2.51.0"
    }
  }
}

provider "digitalocean" {}

locals {
  name        = "<{ digitalocean-name }>"
  vpc_cidr    = "<{ digitalocean-vpc-cidr }>"
  ssh_sources = <{ digitalocean-ssh-sources-json|safe }>
  api_sources = <{ digitalocean-api-sources-json|safe }>
}

resource "digitalocean_vpc" "cluster" {
  name     = "${local.name}-vpc"
  region   = "<{ digitalocean-region }>"
  ip_range = local.vpc_cidr
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

resource "digitalocean_droplet" "control_plane" {
  count    = <{ control-plane-count }>
  name     = "${local.name}-control-plane-${count.index + 1}"
  region   = "<{ digitalocean-region }>"
  size     = "<{ digitalocean-control-plane-size }>"
  image    = "<{ digitalocean-image }>"
  vpc_uuid = digitalocean_vpc.cluster.id
  ssh_keys = ["<{ digitalocean-ssh-key-fingerprint }>"]
  tags     = ["colors-k8s", "${local.name}-control-plane"]
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

resource "digitalocean_droplet" "worker" {
  count    = <{ worker-count }>
  name     = "${local.name}-worker-${count.index + 1}"
  region   = "<{ digitalocean-region }>"
  size     = "<{ digitalocean-worker-size }>"
  image    = "<{ digitalocean-image }>"
  vpc_uuid = digitalocean_vpc.cluster.id
  ssh_keys = ["<{ digitalocean-ssh-key-fingerprint }>"]
  tags     = ["colors-k8s", "${local.name}-worker"]
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

resource "digitalocean_firewall" "control_plane" {
  name        = "${local.name}-control-plane"
  droplet_ids = digitalocean_droplet.control_plane[*].id

  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = local.ssh_sources
  }
  inbound_rule {
    protocol         = "tcp"
    port_range       = "6443"
    source_addresses = local.api_sources
  }
  inbound_rule {
    protocol         = "icmp"
    source_addresses = concat(local.ssh_sources, [local.vpc_cidr])
  }
  inbound_rule {
    protocol         = "tcp"
    port_range       = "1-65535"
    source_addresses = [local.vpc_cidr]
  }
  inbound_rule {
    protocol         = "udp"
    port_range       = "1-65535"
    source_addresses = [local.vpc_cidr]
  }
  outbound_rule {
    protocol              = "tcp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0"]
  }
  outbound_rule {
    protocol              = "udp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0"]
  }
  outbound_rule {
    protocol              = "icmp"
    destination_addresses = ["0.0.0.0/0"]
  }
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

resource "digitalocean_firewall" "worker" {
  name        = "${local.name}-worker"
  droplet_ids = digitalocean_droplet.worker[*].id

  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = local.ssh_sources
  }
  inbound_rule {
    protocol         = "icmp"
    source_addresses = concat(local.ssh_sources, [local.vpc_cidr])
  }
  inbound_rule {
    protocol         = "tcp"
    port_range       = "1-65535"
    source_addresses = [local.vpc_cidr]
  }
  inbound_rule {
    protocol         = "udp"
    port_range       = "1-65535"
    source_addresses = [local.vpc_cidr]
  }
  outbound_rule {
    protocol              = "tcp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0"]
  }
  outbound_rule {
    protocol              = "udp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0"]
  }
  outbound_rule {
    protocol              = "icmp"
    destination_addresses = ["0.0.0.0/0"]
  }
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

output "digitalocean_vpc_id" {
  value = digitalocean_vpc.cluster.id
}
output "control_plane_public_ip" {
  value = digitalocean_droplet.control_plane[0].ipv4_address
}
output "control_plane_private_ip" {
  value = digitalocean_droplet.control_plane[0].ipv4_address_private
}
output "worker_public_ips" {
  value = digitalocean_droplet.worker[*].ipv4_address
}
output "worker_private_ips" {
  value = digitalocean_droplet.worker[*].ipv4_address_private
}
output "params" {
  value = {
    provider = "digitalocean"
    vpc_id   = digitalocean_vpc.cluster.id
    nodes = concat(
      [{
        index  = 0
        role   = "control-plane"
        name   = digitalocean_droplet.control_plane[0].name
        ip     = digitalocean_droplet.control_plane[0].ipv4_address
        vpc_ip = digitalocean_droplet.control_plane[0].ipv4_address_private
        user   = "root"
        sudoer = "root"
      }],
      [for i, w in digitalocean_droplet.worker : {
        index  = i
        role   = "worker"
        name   = w.name
        ip     = w.ipv4_address
        vpc_ip = w.ipv4_address_private
        user   = "root"
        sudoer = "root"
      }]
    )
  }
}
