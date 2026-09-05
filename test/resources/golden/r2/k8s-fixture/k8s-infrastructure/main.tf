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
  name        = "k8s-fixture"
  vpc_cidr    = "10.20.0.0/20"
  ssh_sources = ["203.0.113.10/32"]
  api_sources = ["203.0.113.10/32"]
}

resource "digitalocean_vpc" "cluster" {
  name     = "${local.name}-vpc"
  region   = "ams3"
  ip_range = local.vpc_cidr
  lifecycle { prevent_destroy = true }
}

# Keygen mode (workspace standards/ssh-keypair.md): the account key is named
# after the profile and lives in this stack's state, which is what makes its
# ownership decidable. One key for the cluster, not one per node — the
# deployment is one thing, and a key per machine would multiply what the
# standard exists to make singular. Never reference a literal key id here in
# keygen mode.
resource "digitalocean_ssh_key" "machine" {
  name       = "k8s-fixture"
  public_key = trimspace(file("/home/build-placeholder/.ssh/k8s-fixture.pub"))
}

resource "digitalocean_droplet" "control_plane" {
  count    = 1
  name     = "${local.name}-control-plane-${count.index + 1}"
  region   = "ams3"
  size     = "s-2vcpu-4gb"
  image    = "ubuntu-24-04-x64"
  vpc_uuid = digitalocean_vpc.cluster.id
  ssh_keys = [digitalocean_ssh_key.machine.id]
  tags     = ["colors-k8s", "${local.name}-control-plane"]
  lifecycle { prevent_destroy = true }
}

resource "digitalocean_droplet" "worker" {
  count    = 1
  name     = "${local.name}-worker-${count.index + 1}"
  region   = "ams3"
  size     = "s-2vcpu-4gb"
  image    = "ubuntu-24-04-x64"
  vpc_uuid = digitalocean_vpc.cluster.id
  ssh_keys = [digitalocean_ssh_key.machine.id]
  tags     = ["colors-k8s", "${local.name}-worker"]
  lifecycle { prevent_destroy = true }
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
  lifecycle { prevent_destroy = true }
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
  lifecycle { prevent_destroy = true }
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
    ssh_key_id = digitalocean_ssh_key.machine.id
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
